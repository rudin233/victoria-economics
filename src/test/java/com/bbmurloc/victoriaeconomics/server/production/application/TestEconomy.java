package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.common.definition.*;
import com.bbmurloc.victoriaeconomics.common.definition.building.*;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.*;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.storage.EconomyDatabase;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.RegionalLaborSupply;
import com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollAccrualService;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.financial.JdbcFaults;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** Full real SQLite Staffing/Payroll production fixture. No fake CURRENT, employee identities or cash. */
final class TestEconomy implements AutoCloseable {
    final Object lock=new Object();
    final Connection physical,connection;
    private final boolean ownsConnection;
    final JdbcFaults faults;
    final BuildingRegistry buildings=new BuildingRegistry();
    final BuildingTypeRegistry types=new BuildingTypeRegistry();
    final OccupationRegistry occupations=new OccupationRegistry();
    final ProductionMethodRegistry methods=new ProductionMethodRegistry();
    final ProductionMethodGroupRegistry groups=new ProductionMethodGroupRegistry();
    final ProductionRecipeResolver resolver;
    final SqliteBuildingRepository buildingRepository;
    final SqliteProductionExecutionRepository repository;
    final SqliteProductionMethodConfigurationRepository configurations;
    final SqliteBuildingStaffingRepository staffingRepository;
    final SqliteRegionalLaborSupplyRepository supply;
    final SqliteInventoryStore stock;
    boolean denyStartVerification;
    final BuildingService buildingService;
    final ProductionBoundaryCoordinator coordinator;
    final ProductionService production;
    final PayrollAccrualService accrual;
    final SqliteBuildingPayrollQuery payroll;
    final SqlitePayrollRepository payrollRepository;
    final com.bbmurloc.victoriaeconomics.server.finance.application.FinanceService finance;
    final com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository accounts;
    final com.bbmurloc.victoriaeconomics.server.payroll.application.CompanyPayrollService payments;
    final com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollPeriodService periods;
    final com.bbmurloc.victoriaeconomics.server.household.application.RegionalHouseholdService households;
    final com.bbmurloc.victoriaeconomics.server.building.application.BuildingLifecycleService lifecycle;
    final UUID companyCash,householdCash;
    ServerTickCoordinator clock;
    final UUID id;
    TestEconomy(Path file,boolean create)throws SQLException{this(file,create,1000);}
    TestEconomy(Path file,boolean create,long capacity)throws SQLException{
        this(DriverManager.getConnection("jdbc:sqlite:"+file), true, () -> SqliteEconomyDatabase.open(file), create, capacity);
    }
    TestEconomy(EconomyDatabase runtime, boolean create) throws SQLException {
        this(runtime.getConnection(), false, runtime::openVerificationConnection, create, 1000);
    }
    private TestEconomy(Connection physical, boolean ownsConnection, Supplier<Connection> verificationConnection,
                        boolean create, long capacity) throws SQLException {
        this.physical = physical;
        this.ownsConnection = ownsConnection;
        faults=new JdbcFaults(SqliteConnectionGuard.protect(physical));connection=faults.connection;
        EconomySchema.initialize(connection);TestIdentities.seed(connection);
        DefaultDefinitions.registerBuildingTypes(types);DefaultDefinitions.registerProductionMethods(methods);DefaultDefinitions.registerOccupations(occupations);
        SqliteOccupationCatalog.register(connection,occupations);
        methods.register(new ProductionMethodDefinition("efficient_tools","tooling_workshop_base",2,Map.of("wood",20L,"iron",10L),Map.of("tools",60L),Map.of("laborer",10,"machinist",4)));
        methods.register(new ProductionMethodDefinition("reduced_tools","tooling_workshop_base",2,Map.of("wood",30L,"iron",20L),Map.of("tools",60L),Map.of("laborer",5,"machinist",2)));
        groups.register(new ProductionMethodGroupDefinition("tooling_workshop_base","tooling_workshop",List.of("crude_tools","pig_iron_tools","efficient_tools","reduced_tools")));
        groups.register(new ProductionMethodGroupDefinition("tooling_workshop_automation","tooling_workshop",List.of("hand_assembly")));
        resolver=new ProductionRecipeResolver(types,groups,methods);
        buildingRepository=new SqliteBuildingRepository(connection,lock);repository=new SqliteProductionExecutionRepository(connection,lock);configurations=new SqliteProductionMethodConfigurationRepository(connection,resolver,lock);
        staffingRepository=new SqliteBuildingStaffingRepository(connection,lock);supply=new SqliteRegionalLaborSupplyRepository(connection,occupations,lock);
        buildingRepository.loadAll().forEach(buildings::add);
        var initializer=new ProductionBuildingInitializer(connection,lock,buildingRepository,configurations,repository,resolver,staffingRepository,verificationConnection);
        buildingService=new BuildingService(buildings,buildingRepository,types,initializer,lock);
        stock=new SqliteInventoryStore(connection,lock);
        coordinator=new ProductionBoundaryCoordinator(connection,resolver,occupations,lock, () -> {
            if (denyStartVerification) throw new IllegalStateException("Injected unavailable verification");
            return verificationConnection.get();
        });production=new ProductionService(coordinator,lock);
        accrual=new PayrollAccrualService(connection,lock);payroll=new SqliteBuildingPayrollQuery(connection,lock);payrollRepository=new SqlitePayrollRepository(connection,lock);
        finance=new com.bbmurloc.victoriaeconomics.server.finance.application.FinanceService(connection,lock);
        accounts=new com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository(connection,lock);
        payments=new com.bbmurloc.victoriaeconomics.server.payroll.application.CompanyPayrollService(connection,lock);
        periods=new com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollPeriodService(connection,lock);
        households=new com.bbmurloc.victoriaeconomics.server.household.application.RegionalHouseholdService(connection,lock);
        lifecycle=new com.bbmurloc.victoriaeconomics.server.building.application.BuildingLifecycleService(connection,lock,resolver,production::settleForLifecycle,verificationConnection);
        var currency=new com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId(new UUID(700,1));
        // Explicit zero-cash test accounts, with no issuance. Real zero-payment periods preserve arrears assertions.
        companyCash=finance.open(new UUID(800,1),com.bbmurloc.victoriaeconomics.server.finance.domain.FinanceAccount.OwnerKind.COMPANY,TestIdentities.COMPANY.value(),currency).id();
        householdCash=households.open(TestIdentities.REGION,currency,new UUID(900,1)).sector().accountId();
        if(create){id=createBuilding().getId();stock.createLocation(id,capacity);stock.deposit(id,Map.of("wood",100L,"iron",100L));setSupply("laborer",100);setSupply("machinist",100);}
        else id=buildings.getAll().getFirst().getId();
        recover();
    }
    void recover(){clock=new ServerTickCoordinator(new SqliteEconomicTimeRepository(connection,lock),accrual::recoverAcceptedTime,production::onServerTick,()->{lifecycle.recoverUnfinished();production.recoverAcceptedTick();},periods::recoverAcceptedTime);}
    EconomicBuilding building(){return buildings.get(id);}
    EconomicBuilding createBuilding(){return buildingService.createBuilding("tooling_workshop",TestIdentities.COMPANY,TestIdentities.REGION);}
    ProductionExecution execution(){return repository.load(id);}
    ProductionMethodConfiguration methods(){return configurations.load(id);}
    GoodsInventory.State stockState(){return stock.inspect(id);}
    long quantity(String good){return stockState().quantities().getOrDefault(good,0L);}
    void ticks(int count){for(int i=0;i<count;i++)clock.onServerTick();}
    void setSupply(String role,long total){SqliteTransactions.write(connection,tx->{var repo=supply.inTransaction(tx);var prior=repo.find(TestIdentities.REGION,role);if(prior.isEmpty())repo.create(new RegionalLaborSupply(TestIdentities.REGION,role,total,0));else repo.save(prior.get().withTotal(total),prior.get().revision());return null;});}
    EconomicBuilding createStockedBuilding() {
        var building = createBuilding();
        stock.createLocation(building.getId(), 1000);
        stock.deposit(building.getId(), Map.of("wood", 100L, "iron", 100L));
        return building;
    }

    void beginDemolition() {
        lifecycle.request(new com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.Command(
                UUID.randomUUID(), id, TestIdentities.COMPANY, null,
                com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.Kind.DEMOLITION,
                building().identityRevision()));
    }
    void sql(String sql)throws SQLException{try(var s=connection.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    @Override public void close()throws SQLException{if(ownsConnection)physical.close();}
    void failInventorySettlement(boolean fail) throws SQLException {
        sql(fail ? "CREATE TRIGGER fail_inventory_settlement BEFORE UPDATE ON inventory_locations BEGIN SELECT RAISE(ABORT,'settlement write failed'); END"
                : "DROP TRIGGER fail_inventory_settlement");
    }
    void loseSettlementCommit() {
        faults.beforeSql = sql -> {
            if (sql.startsWith("UPDATE inventory_locations SET state_json")) faults.afterCommit = true;
        };
    }
}
