package com.bbmurloc.victoriaeconomics.server;

import com.bbmurloc.victoriaeconomics.common.definition.*;
import com.bbmurloc.victoriaeconomics.common.definition.building.*;
import com.bbmurloc.victoriaeconomics.common.definition.good.*;
import com.bbmurloc.victoriaeconomics.common.definition.industry.*;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.*;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.common.definition.productionequipment.*;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.inventory.application.ProductionEquipmentRegistry;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.ProductionEquipmentHolding;
import com.bbmurloc.victoriaeconomics.server.production.application.ProductionService;
import com.bbmurloc.victoriaeconomics.server.production.application.EconomicClock;
import com.bbmurloc.victoriaeconomics.server.production.domain.ProductionRecipeResolver;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.inventory.application.ProductionEquipmentService;
import com.bbmurloc.victoriaeconomics.server.workforce.employment.*;
import com.bbmurloc.victoriaeconomics.server.production.application.StaffingCalculator;
import com.bbmurloc.victoriaeconomics.server.storage.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteEmploymentRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteBuildingRepository;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteEquipmentRepository;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionJournal;
import net.minecraft.server.MinecraftServer;
import java.sql.*;
import java.util.UUID;

public final class ServerEconomyContext implements AutoCloseable {
    private final Object economyLock = new Object();
    private final EconomyDatabase database;
    private final GoodRegistry goods = new GoodRegistry();
    private final IndustryRegistry industries = new IndustryRegistry();
    private final BuildingTypeRegistry types = new BuildingTypeRegistry();
    private final ProductionMethodRegistry methods = new ProductionMethodRegistry();
    private final ProductionMethodGroupRegistry groups = new ProductionMethodGroupRegistry();
    private final OccupationRegistry occupations = new OccupationRegistry();
    private final ProductionEquipmentDefinitionRegistry equipmentDefinitions = new ProductionEquipmentDefinitionRegistry();
    private final BuildingRegistry buildings = new BuildingRegistry();
    private final EmploymentRegistry employment = new EmploymentRegistry();
    private final ProductionEquipmentRegistry equipment = new ProductionEquipmentRegistry();
    private final BuildingService buildingService;
    private final ProductionRecipeResolver recipes;
    private final ProductionEquipmentService equipmentService;
    private final EmploymentService employmentService;
    private final StaffingCalculator staffing;
    private final ProductionService production;
    private final SqliteInventoryStore inventory;
    private final EconomicClock clock;

    public ServerEconomyContext(MinecraftServer server) {
        this(server, BuildingPayrollPort.unavailable(), (npc, occupation) -> false);
    }

    public ServerEconomyContext(MinecraftServer server, BuildingPayrollPort payroll,
                                java.util.function.BiPredicate<UUID, String> qualifications) {
        DefaultDefinitions.registerIndustries(industries);
        DefaultDefinitions.registerGoods(goods);
        DefaultDefinitions.registerOccupations(occupations);
        DefaultDefinitions.registerProductionEquipmentDefinitions(equipmentDefinitions);
        DefaultDefinitions.registerProductionMethods(methods);
        DefaultDefinitions.registerProductionMethodGroups(groups);
        DefaultDefinitions.registerBuildingTypes(types);
        DefinitionValidator.validateAll(industries, goods, occupations, equipmentDefinitions, types, groups, methods);
        recipes = new ProductionRecipeResolver(types, groups, methods);
        database = EconomyDatabase.open(server);
        try {
            var connection = database.getConnection();
            var repository = new SqliteBuildingRepository(connection, recipes, economyLock);
            repository.loadAll().forEach(buildings::add);
            var equipmentRepository = new SqliteEquipmentRepository(connection);
            equipmentRepository.loadAll().forEach(equipment::register);
            migrateLegacyEquipment(connection, equipmentRepository);
            equipmentService = new ProductionEquipmentService(buildings, types, equipment, equipmentRepository::saveAll, economyLock);
            var employmentRepository = new SqliteEmploymentRepository(connection);
            employment.replace(employmentRepository.load());
            // No NPC qualification registry or payroll/finance authority exists yet: production must fail closed.
            employmentService = new EmploymentService(employment, buildings, occupations, qualifications, employmentRepository::save, economyLock);
            inventory = new SqliteInventoryStore(connection, economyLock);
            buildingService = new BuildingService(buildings, repository, recipes, economyLock);
            staffing = new StaffingCalculator(employment, types, recipes, equipment);
            production = new ProductionService(buildings, repository, equipmentService, inventory, payroll,
                    employmentService, new SqliteProductionJournal(connection), economyLock);
            production.recoverStarts();
            clock = new EconomicClock(production::onEconomicTick);
        } catch (RuntimeException failure) {
            database.close();
            throw failure;
        }
    }

    private void migrateLegacyEquipment(Connection connection, SqliteEquipmentRepository repository) {
        try (var statement = connection.prepareStatement("SELECT id, current_equipment FROM economic_buildings WHERE current_equipment > 0"); var rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID id = UUID.fromString(rows.getString(1));
                if (equipment.get(id) != null) continue;
                var type = types.get(buildings.get(id).getBuildingTypeId());
                var holding = new ProductionEquipmentHolding(new ProductionEquipmentHolding.State(id, type.productionEquipmentTypeId(),
                        type.maxProductionEquipment(), rows.getInt(2), 0, null, java.util.List.of()));
                repository.save(holding);
                equipment.register(holding);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to preserve legacy equipment data", e);
        }
    }

    public void onServerTick() {
        synchronized (economyLock) {
            clock.onServerTick();
        }
    }

    public BuildingRegistry getBuildingRegistry() {
        return buildings;
    }

    public BuildingService getBuildingService() {
        return buildingService;
    }

    public GoodRegistry getGoodRegistry() {
        return goods;
    }

    public ProductionRecipeResolver getProductionRecipeResolver() {
        return recipes;
    }

    public OccupationRegistry getOccupationRegistry() {
        return occupations;
    }

    public EmploymentRegistry getEmploymentRegistry() {
        return employment;
    }

    public EmploymentService getEmploymentService() {
        return employmentService;
    }

    public StaffingCalculator getStaffingCalculator() {
        return staffing;
    }

    public ProductionService getProductionService() {
        return production;
    }

    public ProductionEquipmentDefinitionRegistry getProductionEquipmentDefinitionRegistry() {
        return equipmentDefinitions;
    }

    public ProductionEquipmentRegistry getProductionEquipmentRegistry() {
        return equipment;
    }

    public ProductionEquipmentService getProductionEquipmentService() {
        return equipmentService;
    }

    public SqliteInventoryStore getInventoryStore() {
        return inventory;
    }

    @Override
    public void close() {
        synchronized (economyLock) {
            database.close();
        }
    }
}
