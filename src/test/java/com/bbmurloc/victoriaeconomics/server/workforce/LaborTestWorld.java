package com.bbmurloc.victoriaeconomics.server.workforce;

import com.bbmurloc.victoriaeconomics.common.definition.*;
import com.bbmurloc.victoriaeconomics.common.definition.building.*;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.*;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.company.domain.*;
import com.bbmurloc.victoriaeconomics.server.company.infrastructure.SqliteCompanyRepository;
import com.bbmurloc.victoriaeconomics.server.region.domain.*;
import com.bbmurloc.victoriaeconomics.server.region.infrastructure.SqliteRegionRepository;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.production.application.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.*;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Isolated persistence fixture. Test helpers for construction, regional supply and batch-bound payroll. */
public final class LaborTestWorld implements AutoCloseable {
    public final Connection physical, connection;
    public final Object lock = new Object();
    public final OccupationRegistry occupations = new OccupationRegistry();
    public final BuildingTypeRegistry types = new BuildingTypeRegistry();
    public final ProductionRecipeResolver recipes;
    public final SqliteBuildingRepository identities;
    public final SqliteCompanyRepository companies;
    public final SqliteRegionRepository regions;
    public final SqliteBuildingStaffingRepository staffing;
    public final SqliteRegionalLaborSupplyRepository supply;
    public final SqliteProductionMethodConfigurationRepository methods;
    public final BuildingRegistry registry = new BuildingRegistry();
    public final BuildingService buildings;
    public final ProductionService production;
    public final ProductionBoundaryCoordinator coordinator;
    public final com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore stock;
    public LaborTestWorld(Path file) throws SQLException { this(file, UnaryOperator.identity()); }
    public LaborTestWorld(Path file, UnaryOperator<Connection> decorate) throws SQLException {
        physical = DriverManager.getConnection("jdbc:sqlite:" + file);
        connection = decorate.apply(SqliteConnectionGuard.protect(physical));
        EconomySchema.initialize(connection);
        var groups = new ProductionMethodGroupRegistry(); var definitions = new ProductionMethodRegistry();
        DefaultDefinitions.registerBuildingTypes(types); DefaultDefinitions.registerProductionMethodGroups(groups);
        DefaultDefinitions.registerProductionMethods(definitions); DefaultDefinitions.registerOccupations(occupations);
        recipes = new ProductionRecipeResolver(types, groups, definitions);
        SqliteOccupationCatalog.register(connection, occupations);
        companies = new SqliteCompanyRepository(connection, lock); regions = new SqliteRegionRepository(connection, lock);
        if (companies.find(TestIdentities.COMPANY).isEmpty()) companies.create(new Company(TestIdentities.COMPANY));
        if (regions.find(TestIdentities.REGION).isEmpty()) regions.create(new Region(TestIdentities.REGION));
        TestIdentities.seed(connection);
        identities = new SqliteBuildingRepository(connection, lock);
        staffing = new SqliteBuildingStaffingRepository(connection, lock);
        supply = new SqliteRegionalLaborSupplyRepository(connection, occupations, lock);
        methods = new SqliteProductionMethodConfigurationRepository(connection, recipes, lock);
        var execution = new SqliteProductionExecutionRepository(connection, lock);
        identities.loadAll().forEach(registry::add);
        var initializer = new ProductionBuildingInitializer(connection, lock, identities, methods, execution, recipes, staffing,
                () -> SqliteEconomyDatabase.open(file));
        buildings = new BuildingService(registry, identities, types, initializer, lock);
        coordinator = new ProductionBoundaryCoordinator(connection, recipes, occupations, lock, () -> SqliteEconomyDatabase.open(file));
        stock = new com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore(connection, lock);
        production = new ProductionService(coordinator, lock);
    }
    public EconomicBuilding create() { return buildings.createBuilding("tooling_workshop", TestIdentities.COMPANY, TestIdentities.REGION); }
    public void setSupply(long amount) { setSupply(TestIdentities.REGION, "laborer", amount); }
    public void setSupply(RegionId region, String role, long amount) {
        SqliteTransactions.write(connection, tx -> {
            var repository = supply.inTransaction(tx);
            var before = repository.find(region, role);
            if (before.isEmpty()) repository.create(new RegionalLaborSupply(region, role, amount, 0));
            else repository.save(before.get().withTotal(amount), before.get().revision());
            return null;
        });
    }

    public void stock(UUID building) {
        stock.createLocation(building, 1000);
        stock.deposit(building, Map.of("wood", 100L, "iron", 100L));
    }

    /** 财务测试使用仅含单一职业的合成批次，隔离配方与付款规则。 */
    public ProductionBatch acquirePayrollFixture(SqliteTransactions.WriteSession tx, UUID id, UUID batchId, int count, long tick) throws SQLException {
        var wage = new com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollRepository(connection, lock).inTransaction(tx);
        var segment = wage.currentForBuilding(id);
        wage.accrue(segment, tick);
        var executionRepo = new SqliteProductionExecutionRepository(connection, lock).inTransaction(tx);
        var execution = executionRepo.load(id);
        var roster = Map.of("laborer", count);
        var recipe = new ResolvedProductionRecipe(Map.of(), Map.of("tools", 1L), roster);
        var config = new ProductionBatchConfiguration(Map.of("payroll_fixture", "labor"), recipe, new WorkforcePlan(roster, 1, 1));
        var batch = execution.start(batchId, config, tick);
        executionRepo.save(execution, execution.executionRevision());
        var repo = staffing.inTransaction(tx);
        repo.acquire(id, batch.getId(), roster, repo.load(id).revision());
        wage.changeEmployees(segment.id(), count, tick);
        return batch;
    }

    public ProductionBatch startPayrollFixture(UUID id, int workers) { return startPayrollFixture(id, workers, true); }

    public ProductionBatch startPayrollFixture(UUID id, int workers, boolean createStock) {
        if (workers <= 0) return null;
        var region = identities.load(id).regionId();
        var total = supply.find(region, "laborer").map(RegionalLaborSupply::totalSupply).orElse(0L);
        long required = Math.addExact(supply.occupied(region, "laborer"), workers);
        if (required > total) setSupply(region, "laborer", required);
        if (createStock) stock.createLocation(id, 1000);
        var batchId = UUID.randomUUID();
        return SqliteTransactions.write(connection, tx -> {
            stock.reserve(tx, id, batchId, Map.of());
            long tick = com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick();
            return acquirePayrollFixture(tx, id, batchId, workers, tick);
        });
    }

    public long count(String table) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { row.next(); return row.getLong(1); }
    }
    public void sql(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    @Override public void close() throws SQLException { physical.close(); }
}
