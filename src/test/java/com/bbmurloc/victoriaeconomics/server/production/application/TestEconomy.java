package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.common.definition.*;
import com.bbmurloc.victoriaeconomics.common.definition.building.*;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.*;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.port.ProductionInventoryPort;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.inventory.application.ProductionEquipmentRegistry;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.inventory.application.ProductionEquipmentService;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.EconomySchema;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteEmploymentRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteBuildingRepository;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteEquipmentRepository;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionJournal;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionExecution;

/**
 * Actual file-backed SQLite inventories/repositories, with explicit external payroll/qualification test facts.
 */
final class TestEconomy implements AutoCloseable {
    final Object lock = new Object();
    final Connection connection;
    final BuildingRegistry buildings = new BuildingRegistry();
    final BuildingTypeRegistry types = new BuildingTypeRegistry();
    final ProductionMethodRegistry methods = new ProductionMethodRegistry();
    final ProductionMethodGroupRegistry groups = new ProductionMethodGroupRegistry();
    final EmploymentRegistry employees = new EmploymentRegistry();
    final ProductionEquipmentRegistry holdings = new ProductionEquipmentRegistry();
    final OccupationRegistry occupations = new OccupationRegistry();
    final AtomicReference<BuildingPayrollPort.State> payroll = new AtomicReference<>(BuildingPayrollPort.State.CURRENT);
    final FaultRepository repository;
    final SqliteProductionJournal journal;
    final FaultInventory inventory;
    final ProductionEquipmentService equipment;
    final EmploymentService employment;
    final BuildingService buildingService;
    final ProductionService production;
    final SqliteInventoryStore stock;
    final EconomicClock clock;
    final UUID id;

    TestEconomy(Path file, boolean create) throws SQLException {
        this(file, create, 1000);
    }

    TestEconomy(Path file, boolean create, double capacity) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        EconomySchema.initialize(connection);
        DefaultDefinitions.registerBuildingTypes(types);
        DefaultDefinitions.registerProductionMethods(methods);
        DefaultDefinitions.registerOccupations(occupations);
        methods.register(new ProductionMethodDefinition("efficient_tools", "tooling_workshop_base", 2,
                Map.of("wood", 20.0, "iron", 10.0), Map.of("tools", 60.0), Map.of("laborer", 10, "machinist", 4)));
        groups.register(new ProductionMethodGroupDefinition("tooling_workshop_base", "tooling_workshop", List.of("crude_tools", "pig_iron_tools", "efficient_tools")));
        groups.register(new ProductionMethodGroupDefinition("tooling_workshop_automation", "tooling_workshop", List.of("hand_assembly")));
        var resolver = new ProductionRecipeResolver(types, groups, methods);
        repository = new FaultRepository(new SqliteBuildingRepository(connection, resolver, lock));
        repository.loadAll().forEach(buildings::add);
        var equipmentRepository = new SqliteEquipmentRepository(connection);
        equipmentRepository.loadAll().forEach(holdings::register);
        equipment = new ProductionEquipmentService(buildings, types, holdings, equipmentRepository::saveAll, lock);
        var employmentRepository = new SqliteEmploymentRepository(connection);
        employees.replace(employmentRepository.load());
        employment = new EmploymentService(employees, buildings, occupations, (npc, occupation) -> true, employmentRepository::save, lock);
        stock = new SqliteInventoryStore(connection, lock);
        inventory = new FaultInventory(stock);
        journal = new SqliteProductionJournal(connection);
        buildingService = new BuildingService(buildings, repository, resolver, lock);
        production = new ProductionService(buildings, repository, equipment, inventory, (building) -> payroll.get(), employment, journal, lock);
        clock = new EconomicClock(production::onEconomicTick);
        if (create) {
            id = buildingService.createBuilding("tooling_workshop").getId();
            stock.createLocation(id, capacity);
            stock.deposit(id, Map.of("wood", 100.0, "iron", 100.0));
            equipment.addUninstalledEquipment(id, 100);
            equipment.install(id, 100);
            for (int i = 0; i < 10; i++) employment.hire(new UUID(1, i + 1), id, "laborer");
            for (int i = 0; i < 4; i++) employment.hire(new UUID(2, i + 1), id, "machinist");
        } else {
            id = buildings.getAll().getFirst().getId();
            production.recoverStarts();
        }
    }

    EconomicBuilding building() {
        return buildings.get(id);
    }

    ProductionExecution execution() {
        return building().getProductionDepartment().getExecution();
    }

    GoodsInventory.State stockState() {
        return stock.inspect(id);
    }

    double quantity(String good) {
        return stockState().quantities().getOrDefault(good, 0.0);
    }

    void ticks(int count) {
        for (int i = 0; i < count; i++) clock.onServerTick();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    static final class FaultRepository implements BuildingRepository {
        final BuildingRepository delegate;
        boolean failNextSave;
        boolean failCompletedSave;
        boolean failMethodsSave;
        boolean failEquipmentBoundarySave;
        boolean loseStartAcknowledgement;

        FaultRepository(BuildingRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(EconomicBuilding building) {
            var batch = building.getProductionDepartment().getActiveBatch();
            if (failNextSave || (failCompletedSave && batch != null && batch.isEnded())) {
                failNextSave = false;
                failCompletedSave = false;
                throw new IllegalStateException("Injected checkpoint failure");
            }
            if (failMethodsSave && building.getProductionDepartment().getExecution().boundary() == ProductionExecution.Boundary.EQUIPMENT) {
                failMethodsSave = false;
                throw new IllegalStateException("Injected PM boundary failure");
            }
            if (failEquipmentBoundarySave && building.getProductionDepartment().getExecution().boundary() == ProductionExecution.Boundary.WORKFORCE) {
                failEquipmentBoundarySave = false;
                throw new IllegalStateException("Injected equipment boundary failure");
            }
            delegate.save(building);
            if (loseStartAcknowledgement && batch != null && batch.isActive() && batch.getProgress() == 0) {
                loseStartAcknowledgement = false;
                throw new IllegalStateException("Injected lost start commit acknowledgement");
            }
        }

        @Override
        public List<EconomicBuilding> loadAll() {
            return delegate.loadAll();
        }

        @Override
        public void delete(UUID id) {
            delegate.delete(id);
        }
    }

    static final class FaultInventory implements ProductionInventoryPort {
        final SqliteInventoryStore delegate;
        boolean failAfterReserve, failRelease, reserveOnlyFirstMaterial;
        Runnable afterReserve = () -> {
        };

        FaultInventory(SqliteInventoryStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public GoodsInventory.State inspect(UUID location) {
            return delegate.inspect(location);
        }

        @Override
        public void reserve(UUID location, UUID batch, Map<String, Double> inputs) {
            if (reserveOnlyFirstMaterial) {
                var first = new TreeMap<>(inputs).firstEntry();
                delegate.reserve(location, batch, Map.of(first.getKey(), first.getValue()));
                throw new IllegalStateException("Injected partial multi-material reservation failure");
            }
            delegate.reserve(location, batch, inputs);
            afterReserve.run();
            if (failAfterReserve) throw new IllegalStateException("Injected lost reservation acknowledgement");
        }

        @Override
        public boolean isReserved(UUID location, UUID batch, Map<String, Double> inputs) {
            return delegate.isReserved(location, batch, inputs);
        }

        @Override
        public void release(UUID location, UUID batch) {
            if (failRelease) throw new IllegalStateException("Injected release failure");
            delegate.release(location, batch);
        }

        @Override
        public GoodsInventory.Settlement settle(UUID location, UUID batch, Map<String, Double> inputs, Map<String, Double> outputs, double progress) {
            return delegate.settle(location, batch, inputs, outputs, progress);
        }
    }
}
