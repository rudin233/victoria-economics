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
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionMethodConfigurationRepository;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.RepositoryProductionFacts;
import com.bbmurloc.victoriaeconomics.server.production.application.ProductionBuildingInitializer;
import net.minecraft.server.MinecraftServer;
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
            var repository = new SqliteBuildingRepository(connection, economyLock);
            var methodRepository = new SqliteProductionMethodConfigurationRepository(connection, recipes, economyLock);
            var executionRepository = new SqliteProductionExecutionRepository(connection, economyLock);
            repository.loadAll().forEach(buildings::add);
            buildings.getAll().forEach(b -> { methodRepository.load(b.getId()); executionRepository.load(b.getId()); });
            var productionFacts = new RepositoryProductionFacts(methodRepository, executionRepository, economyLock);
            var equipmentRepository = new SqliteEquipmentRepository(connection);
            equipmentRepository.loadAll().forEach(equipment::register);
            equipmentService = new ProductionEquipmentService(buildings, types, equipment, equipmentRepository::saveAll, productionFacts, economyLock);
            var employmentRepository = new SqliteEmploymentRepository(connection);
            employment.replace(employmentRepository.load());
            // No NPC qualification registry or payroll/finance authority exists yet: production must fail closed.
            employmentService = new EmploymentService(employment, buildings, occupations, qualifications, employmentRepository::save, productionFacts, economyLock);
            inventory = new SqliteInventoryStore(connection, economyLock);
            var initializer = new ProductionBuildingInitializer(connection, economyLock, repository, methodRepository, executionRepository, recipes);
            buildingService = new BuildingService(buildings, repository, types, initializer::initialize, economyLock);
            staffing = new StaffingCalculator(employment, types, methodRepository, equipment);
            production = new ProductionService(buildings, methodRepository, executionRepository, equipmentService, inventory, payroll,
                    employmentService, new SqliteProductionJournal(connection), economyLock);
            production.recoverStarts();
            production.recoverBoundaries();
            clock = new EconomicClock(production::onEconomicTick);
        } catch (RuntimeException failure) {
            database.close();
            throw failure;
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
