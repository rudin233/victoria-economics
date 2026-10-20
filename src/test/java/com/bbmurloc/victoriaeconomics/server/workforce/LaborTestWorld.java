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
import com.bbmurloc.victoriaeconomics.server.workforce.application.*;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Isolated persistence fixture. Low-level staffing writes deliberately do NOT represent formal payroll-aware hiring. */
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
    public final StaffingApplicationService commands;
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
        staffing = new SqliteBuildingStaffingRepository(connection, recipes, occupations, lock);
        supply = new SqliteRegionalLaborSupplyRepository(connection, occupations, lock);
        methods = new SqliteProductionMethodConfigurationRepository(connection, recipes, lock);
        var execution = new SqliteProductionExecutionRepository(connection, lock);
        identities.loadAll().forEach(registry::add);
        var initializer = new ProductionBuildingInitializer(connection, lock, identities, methods, execution, recipes, staffing,
                () -> SqliteEconomyDatabase.open(file));
        buildings = new BuildingService(registry, identities, types, initializer, lock);
        commands = new StaffingApplicationService(connection, recipes, occupations, lock);
    }
    public EconomicBuilding create() { return buildings.createBuilding("tooling_workshop", TestIdentities.COMPANY, TestIdentities.REGION); }
    public void setSupply(long amount) {
        SqliteTransactions.write(connection, tx -> {
            var repository = supply.inTransaction(tx);
            var before = repository.find(TestIdentities.REGION, "laborer");
            if (before.isEmpty()) repository.create(new RegionalLaborSupply(TestIdentities.REGION, "laborer", amount, 0));
            else repository.save(before.get().withTotal(amount), before.get().revision());
            return null;
        });
    }
    public void applyPendingForPersistenceTest(UUID building) {
        SqliteTransactions.write(connection, tx -> {
            var payroll=new com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollRepository(connection,lock).inTransaction(tx);
            var segment=payroll.currentForBuilding(building);
            payroll.accrue(segment,com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick());
            var repository = staffing.inTransaction(tx); var root = repository.load(building);
            var region = identities.inTransaction(tx).load(building).regionId();
            var caps = methods.inTransaction(tx).load(building).recipe().requiredWorkers();
            var available = new HashMap<String, Long>();
            for (String role : caps.keySet()) {
                long total = supply.inTransaction(tx).find(region, role).map(RegionalLaborSupply::totalSupply).orElse(0L);
                available.put(role, Math.subtractExact(total, supply.inTransaction(tx).occupied(region, role)));
            }
            var changed=root.apply(StaffingTransition.consumePending(root,caps,available));
            repository.save(changed,root.revision());
            long count=0;for(int n:changed.employed().values())count=Math.addExact(count,n);
            if(count!=segment.employees())payroll.changeEmployees(segment.id(),count,com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick());
            return null;
        });
    }
    /** Repository Pending fixture, distinct from the formal idle-immediate application command. */
    public void requestPendingForPersistenceTest(UUID building,String role,int target){
        SqliteTransactions.write(connection,tx->{var repo=staffing.inTransaction(tx);var before=repo.load(building);var changed=before.requestTarget(role,target,methods.inTransaction(tx).load(building).recipe().requiredWorkers());if(changed!=before)repo.save(changed,before.revision());return null;});
    }
    public void cancelPendingForPersistenceTest(UUID building,String role){SqliteTransactions.write(connection,tx->{var repo=staffing.inTransaction(tx);var before=repo.load(building);var changed=before.cancel(role);if(changed!=before)repo.save(changed,before.revision());return null;});}
    public long count(String table) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { row.next(); return row.getLong(1); }
    }
    public void sql(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    @Override public void close() throws SQLException { physical.close(); }
}
