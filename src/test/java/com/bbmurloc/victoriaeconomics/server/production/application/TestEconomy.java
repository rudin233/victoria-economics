package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.common.definition.*;
import com.bbmurloc.victoriaeconomics.common.definition.building.*;
import com.bbmurloc.victoriaeconomics.common.definition.occupation.*;
import com.bbmurloc.victoriaeconomics.common.definition.production.*;
import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.RegionalLaborSupply;
import com.bbmurloc.victoriaeconomics.server.workforce.application.StaffingApplicationService;
import com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollAccrualService;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.financial.JdbcFaults;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Full real SQLite Staffing/Payroll production fixture. No fake CURRENT, employee identities or cash. */
final class TestEconomy implements AutoCloseable {
    final Object lock=new Object();
    final Connection physical,connection;
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
    final FaultInventory inventory;
    final FaultJournal journal;
    final BuildingService buildingService;
    final ProductionBoundaryCoordinator coordinator;
    final ProductionService production;
    final StaffingApplicationService staffing;
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
    EconomicClock clock;
    final UUID id;
    TestEconomy(Path file,boolean create)throws SQLException{this(file,create,1000);}
    TestEconomy(Path file,boolean create,long capacity)throws SQLException{
        physical=DriverManager.getConnection("jdbc:sqlite:"+file);faults=new JdbcFaults(SqliteConnectionGuard.protect(physical));connection=faults.connection;
        EconomySchema.initialize(connection);TestIdentities.seed(connection);
        DefaultDefinitions.registerBuildingTypes(types);DefaultDefinitions.registerProductionMethods(methods);DefaultDefinitions.registerOccupations(occupations);
        SqliteOccupationCatalog.register(connection,occupations);
        methods.register(new ProductionMethodDefinition("efficient_tools","tooling_workshop_base",2,Map.of("wood",20L,"iron",10L),Map.of("tools",60L),Map.of("laborer",10,"machinist",4)));
        methods.register(new ProductionMethodDefinition("reduced_tools","tooling_workshop_base",2,Map.of("wood",30L,"iron",20L),Map.of("tools",60L),Map.of("laborer",5,"machinist",2)));
        groups.register(new ProductionMethodGroupDefinition("tooling_workshop_base","tooling_workshop",List.of("crude_tools","pig_iron_tools","efficient_tools","reduced_tools")));
        groups.register(new ProductionMethodGroupDefinition("tooling_workshop_automation","tooling_workshop",List.of("hand_assembly")));
        resolver=new ProductionRecipeResolver(types,groups,methods);
        buildingRepository=new SqliteBuildingRepository(connection,lock);repository=new SqliteProductionExecutionRepository(connection,lock);configurations=new SqliteProductionMethodConfigurationRepository(connection,resolver,lock);
        staffingRepository=new SqliteBuildingStaffingRepository(connection,resolver,occupations,lock);supply=new SqliteRegionalLaborSupplyRepository(connection,occupations,lock);
        buildingRepository.loadAll().forEach(buildings::add);
        var initializer=new ProductionBuildingInitializer(connection,lock,buildingRepository,configurations,repository,resolver,staffingRepository,()->SqliteEconomyDatabase.open(file));
        buildingService=new BuildingService(buildings,buildingRepository,types,initializer,lock);
        stock=new SqliteInventoryStore(connection,lock);inventory=new FaultInventory(stock);journal=new FaultJournal(new SqliteProductionJournal(connection));
        coordinator=new ProductionBoundaryCoordinator(connection,resolver,occupations,lock);production=new ProductionService(coordinator,inventory,journal,lock);
        staffing=new StaffingApplicationService(coordinator,production::retry);accrual=new PayrollAccrualService(connection,lock);payroll=new SqliteBuildingPayrollQuery(connection,lock);payrollRepository=new SqlitePayrollRepository(connection,lock);
        finance=new com.bbmurloc.victoriaeconomics.server.finance.application.FinanceService(connection,lock);
        accounts=new com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository(connection,lock);
        payments=new com.bbmurloc.victoriaeconomics.server.payroll.application.CompanyPayrollService(connection,lock);
        periods=new com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollPeriodService(connection,lock);
        households=new com.bbmurloc.victoriaeconomics.server.household.application.RegionalHouseholdService(connection,lock);
        lifecycle=new com.bbmurloc.victoriaeconomics.server.building.application.BuildingLifecycleService(connection,lock,resolver,production::settleForLifecycle,()->SqliteEconomyDatabase.open(file));
        var currency=new com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId(new UUID(700,1));
        // Explicit zero-cash test accounts, with no issuance. Real zero-payment periods preserve arrears assertions.
        companyCash=finance.open(new UUID(800,1),com.bbmurloc.victoriaeconomics.server.finance.domain.FinanceAccount.OwnerKind.COMPANY,TestIdentities.COMPANY.value(),currency).id();
        householdCash=households.open(TestIdentities.REGION,currency,new UUID(900,1)).sector().accountId();
        if(create){id=createBuilding().getId();stock.createLocation(id,capacity);stock.deposit(id,Map.of("wood",100L,"iron",100L));setSupply("laborer",100);setSupply("machinist",100);staffing.requestTarget(id,"laborer",10);staffing.requestTarget(id,"machinist",4);}
        else id=buildings.getAll().getFirst().getId();
        recover();
    }
    void recover(){clock=new EconomicClock(new SqliteEconomicTimeRepository(connection,lock),accrual::recoverAcceptedTime,production::onEconomicTick,()->{lifecycle.recoverUnfinished();production.recoverAcceptedTick();},periods::recoverAcceptedTime);}
    EconomicBuilding building(){return buildings.get(id);}
    EconomicBuilding createBuilding(){return buildingService.createBuilding("tooling_workshop",TestIdentities.COMPANY,TestIdentities.REGION);}
    ProductionExecution execution(){return repository.load(id);}
    ProductionMethodConfiguration methods(){return configurations.load(id);}
    GoodsInventory.State stockState(){return stock.inspect(id);}
    long quantity(String good){return stockState().quantities().getOrDefault(good,0L);}
    void ticks(int count){for(int i=0;i<count;i++)clock.onServerTick();}
    void setSupply(String role,long total){SqliteTransactions.write(connection,tx->{var repo=supply.inTransaction(tx);var prior=repo.find(TestIdentities.REGION,role);if(prior.isEmpty())repo.create(new RegionalLaborSupply(TestIdentities.REGION,role,total,0));else repo.save(prior.get().withTotal(total),prior.get().revision());return null;});}
    /** Isolated concurrent persistence injection; not a formal application admission path. */
    void staffingRaceFixture(String role,int target){SqliteTransactions.write(connection,tx->{
        var wages=payrollRepository.inTransaction(tx);var p=wages.currentForBuilding(id);long tick=SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick();
        wages.accrue(p,tick);var repo=staffingRepository.inTransaction(tx);var prior=repo.load(id);var counts=new TreeMap<>(prior.employed());counts.put(role,target);
        var changed=new com.bbmurloc.victoriaeconomics.server.workforce.domain.BuildingStaffing(id,counts,prior.pending(),Math.incrementExact(prior.revision()));repo.save(changed,prior.revision());
        long sum=0;for(int count:changed.employed().values())sum=Math.addExact(sum,count);wages.changeEmployees(p.id(),sum,tick);return null;
    });}
    void sql(String sql)throws SQLException{try(var s=connection.createStatement()){s.execute(sql);}}
    long scalar(String sql)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    @Override public void close()throws SQLException{physical.close();}
    static final class FaultJournal implements ProductionJournal {
        final ProductionJournal delegate;boolean failContains,failReads,failCleanup;
        FaultJournal(ProductionJournal delegate){this.delegate=delegate;}
        public void recordStart(StartIntent i){delegate.recordStart(i);}
        public void finishStart(UUID id){if(failCleanup)throw new IllegalStateException("Injected journal cleanup failure");delegate.finishStart(id);}
        public List<StartIntent> pendingStarts(){if(failReads)throw new IllegalStateException("Injected unknown start intents");return delegate.pendingStarts();}
        public boolean containsBatch(UUID id){if(failContains)throw new IllegalStateException("Injected unknown start evidence");return delegate.containsBatch(id);}
    }
    static final class FaultInventory implements ProductionInventoryPort {
        final SqliteInventoryStore delegate;
        boolean failAfterReserve,failRelease,reserveOnlyFirstMaterial,failSettlement,loseSettlementAcknowledgement;
        Runnable afterReserve=()->{};
        FaultInventory(SqliteInventoryStore delegate){this.delegate=delegate;}
        public StorageStatus storageStatus(UUID id){return delegate.storageStatus(id);}
        public void reserve(UUID id,UUID batch,Map<String,Long> inputs){
            if(reserveOnlyFirstMaterial){var first=new TreeMap<>(inputs).firstEntry();delegate.reserve(id,batch,Map.of(first.getKey(),first.getValue()));throw new IllegalStateException("Injected partial reservation failure");}
            delegate.reserve(id,batch,inputs);afterReserve.run();if(failAfterReserve)throw new IllegalStateException("Injected lost reserve acknowledgement");
        }
        public boolean isReserved(UUID id,UUID batch,Map<String,Long> inputs){return delegate.isReserved(id,batch,inputs);}
        public void release(UUID id,UUID batch){if(failRelease)throw new IllegalStateException("Injected release failure");delegate.release(id,batch);}
        public SettlementReceipt settle(UUID id,ProductionSettlement command){if(failSettlement)throw new IllegalStateException("Injected settlement failure");var receipt=delegate.settle(id,command);if(loseSettlementAcknowledgement){loseSettlementAcknowledgement=false;throw new CommitOutcomeUnknownException("Injected lost settlement receipt",null);}return receipt;}
    }
}
