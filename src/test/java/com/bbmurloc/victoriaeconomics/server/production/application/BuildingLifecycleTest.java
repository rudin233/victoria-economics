package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingLifecycleTest {
    @TempDir Path dir;
    static final CompanyId BUYER=new CompanyId(new UUID(101,2));
    Path file(){return dir.resolve("lifecycle.sqlite");}
    static Command command(TestEconomy e,Kind kind)throws Exception{
        e.sql("INSERT INTO companies VALUES('"+BUYER+"') ON CONFLICT DO NOTHING");
        return new Command(UUID.randomUUID(),e.id,TestIdentities.COMPANY,kind==Kind.SALE?BUYER:null,kind,e.buildingRepository.load(e.id).identityRevision());
    }
    @Test void readOnlyAssessmentDoesNotStopProductionReserveResourcesOrCreateAnOperation()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var batch=e.production.startBatch(e.id);e.ticks(3);var cmd=command(e,Kind.SALE);var execution=e.execution();var stock=e.stockState();
            var assessment=e.lifecycle.assess(cmd);assertFalse(assessment.finalizationAvailable());assertEquals(14,assessment.employees());assertEquals(batch.getId(),assessment.batchId());
            assertEquals(200,assessment.inventoryUnits());assertEquals(1,assessment.reservations());assertEquals(3,assessment.acceptedTick());
            assertEquals(execution.executionRevision(),e.execution().executionRevision());assertTrue(e.execution().automatic());assertEquals(stock,e.stockState());assertEquals(0,e.scalar("SELECT COUNT(*) FROM building_lifecycle_operations"));
        }
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void runningOperationRefundsAllInputsButRetainsOwnerStaffPendingAndWageAuthority(Kind kind)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var batch=e.production.startBatch(e.id);e.ticks(37);
            e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");e.staffing.requestTarget(e.id,"laborer",5);
            var staff=e.staffingRepository.load(e.id);var pm=e.methods();var cmd=command(e,kind);
            var op=e.lifecycle.request(cmd);
            assertEquals(Status.BLOCKED,op.status());assertFalse(e.execution().automatic());assertNull(e.execution().batch());
            assertEquals(batch.getId(),e.execution().lastBatch().getId());assertEquals(ProductionBatch.Status.ABORTED,e.execution().lastBatch().getStatus());
            assertEquals(37,e.execution().lastBatch().getProgressUnits());assertEquals(100,e.quantity("wood"));assertEquals(0,e.quantity("tools"));assertTrue(e.stockState().reservations().isEmpty());
            assertEquals(staff,e.staffingRepository.load(e.id));assertEquals(pm.effective().methods(),e.methods().effective().methods());assertEquals(pm.pending(),e.methods().pending());
            assertEquals(TestIdentities.COMPANY,e.buildingRepository.load(e.id).ownerCompanyId());assertEquals(BuildingLifecycle.EXISTING,e.buildingRepository.load(e.id).lifecycle());
            assertEquals(10,e.supply.occupied(TestIdentities.REGION,"laborer"));assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));
            assertTrue(op.blockers().contains(Blocker.WAGE_REMAINDER_POLICY_UNDECIDED));
            if(kind==Kind.SALE)assertTrue(op.blockers().contains(Blocker.PENDING_PM_POLICY_UNDECIDED));
            var stock=e.stockState();long revision=e.execution().executionRevision();
            assertEquals(op,e.lifecycle.request(cmd));assertEquals(stock,e.stockState());assertEquals(revision,e.execution().executionRevision());
            assertEquals(op,e.lifecycle.verify(cmd).orElseThrow());EconomySchema.validateExisting(e.connection);
            e.ticks(1);assertEquals(38,e.clock.elapsedTicks());assertEquals(38,e.scalar("SELECT last_accrued_tick FROM payroll_accruals WHERE closed_tick IS NULL"));assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"IDLE","PAUSED","SETTLING_ABORTED","SETTLING_COMPLETED"})
    void everyExistingBatchDecisionHasOneMatchingSettlement(String stage)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            if(!stage.equals("IDLE"))e.production.startBatch(e.id);
            switch(stage){
                case "PAUSED"->{e.ticks(7);e.production.setManualPause(e.id,true);}
                case "SETTLING_ABORTED"->{e.ticks(7);e.coordinator.abort(e.id);}
                case "SETTLING_COMPLETED"->{e.ticks(199);new SqliteEconomicTimeRepository(e.connection,e.lock).acceptNext(199);e.accrual.recoverAcceptedTime();e.coordinator.advance(e.id,200);}
            }
            var op=e.lifecycle.request(command(e,Kind.SALE));assertEquals(Status.BLOCKED,op.status());assertNull(e.execution().batch());
            if(stage.equals("IDLE")){assertNull(op.batchId());assertTrue(e.stockState().settlements().isEmpty());}
            else {
                boolean completed=stage.equals("SETTLING_COMPLETED");
                assertEquals(completed?ProductionSettlement.Outcome.COMPLETED:ProductionSettlement.Outcome.ABORTED,op.settlement());
                assertEquals(completed?30:0,e.quantity("tools"));assertEquals(completed?70:100,e.quantity("wood"));assertEquals(1,e.stockState().settlements().size());
            }
            e.recover();e.ticks(1);assertEquals(Status.BLOCKED,e.lifecycle.find(op.command().id()).orElseThrow().status());assertTrue(e.stockState().reservations().isEmpty());
        }
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void realArrearsPauseIsAbortedWithoutLosingDebtRemainderOrRemainingLabor(Kind kind)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.staffing.requestTarget(e.id,"laborer",1);e.staffing.requestTarget(e.id,"machinist",1);e.production.startBatch(e.id);e.ticks(1201);
            assertEquals(ProductionBatch.Status.PAUSED,e.execution().batch().getStatus());long debt=e.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"),fraction=e.scalar("SELECT remainder FROM payroll_accruals");
            assertEquals(2,debt);assertEquals(2,fraction);var op=e.lifecycle.request(command(e,kind));
            assertEquals(Status.BLOCKED,op.status());assertEquals(2,e.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));assertEquals(fraction,e.scalar("SELECT remainder FROM payroll_accruals"));
            assertEquals(2,e.scalar("SELECT employees FROM payroll_accruals WHERE closed_tick IS NULL"));assertEquals(100,e.quantity("wood"));assertEquals(0,e.quantity("tools"));
            assertEquals(com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State.ARREARS,e.payroll.state(e.id));
            e.ticks(2);assertEquals(6,e.scalar("SELECT remainder FROM payroll_accruals"));assertEquals(2,e.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));EconomySchema.validateExisting(e.connection);
        }
    }
    @Test void blockedIdentityCannotStartReconfigureHireResumeOrBypassWithLowLevelRepositories()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");var op=e.lifecycle.request(command(e,Kind.DEMOLITION));
            assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));assertThrows(IllegalStateException.class,()->e.production.setAutomatic(e.id,true));
            assertThrows(IllegalStateException.class,()->e.production.setManualPause(e.id,false));assertThrows(IllegalStateException.class,()->e.staffing.requestTarget(e.id,"laborer",0));
            assertThrows(IllegalStateException.class,()->e.staffing.cancelPending(e.id,"laborer"));assertThrows(IllegalStateException.class,()->e.production.cancelPendingProductionMethods(e.id));
            assertThrows(IllegalStateException.class,()->e.production.selectProductionMethod(e.id,"tooling_workshop_base","efficient_tools"));
            assertThrows(IllegalStateException.class,()->e.buildingService.stopBuilding(e.id));
            var execution=e.execution();execution.requestAutomatic(true);assertThrows(IllegalStateException.class,()->e.repository.save(execution,execution.executionRevision()));
            var forged=e.execution();forged.start(UUID.randomUUID(),forged.lastBatch().getConfiguration());forged.requestAutomatic(false);forged.tick(200);
            assertThrows(IllegalStateException.class,()->e.repository.save(forged,forged.executionRevision()));
            var pm=e.methods();long pmRev=pm.configurationRevision();pm.cancelPending();assertThrows(IllegalStateException.class,()->e.configurations.save(pm,pmRev));
            var capacity=e.methods().recipe().requiredWorkers();
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(e.connection,tx->{
                var repo=e.staffingRepository.inTransaction(tx);var staff=repo.load(e.id);repo.save(staff.requestTarget("laborer",0,capacity),staff.revision());return null;
            }));
            // Ordinary goods retain their own rules and can be handled without implying asset ownership.
            e.stock.withdraw(e.id,Map.of("wood",1L));e.stock.deposit(e.id,Map.of("tools",1L));assertEquals(99,e.quantity("wood"));
            assertEquals(op,e.lifecycle.find(op.command().id()).orElseThrow());
        }
    }
    @Test void missingBuyerStaleIdentityAndConflictingCommandsHaveNoAdditionalEffects()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);var cmd=command(e,Kind.SALE);
            var missing=new Command(cmd.id(),e.id,cmd.seller(),new CompanyId(UUID.randomUUID()),Kind.SALE,0);
            assertThrows(IllegalArgumentException.class,()->e.lifecycle.request(missing));assertEquals(0,e.scalar("SELECT COUNT(*) FROM building_lifecycle_operations"));assertTrue(e.execution().automatic());assertTrue(e.execution().batch().isActive());
            var stale=new Command(cmd.id(),e.id,cmd.seller(),cmd.buyer(),cmd.kind(),1);assertThrows(IllegalStateException.class,()->e.lifecycle.request(stale));
            var op=e.lifecycle.request(cmd);
            assertThrows(IllegalArgumentException.class,()->e.lifecycle.request(new Command(cmd.id(),e.id,cmd.seller(),null,Kind.DEMOLITION,0)));
            assertThrows(IllegalStateException.class,()->e.lifecycle.request(new Command(UUID.randomUUID(),e.id,cmd.seller(),null,Kind.DEMOLITION,0)));
            assertEquals(op,e.lifecycle.find(cmd.id()).orElseThrow());assertEquals(1,e.stockState().settlements().size());
        }
    }
    @Test void operationDuringStartReservationCompensatesIntentAndRejectsFinalCommit()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var cmd=command(e,Kind.SALE);e.inventory.afterReserve=()->e.lifecycle.request(cmd);
            assertThrows(IllegalStateException.class,()->e.production.startBatch(e.id));
            assertNull(e.execution().batch());assertTrue(e.journal.pendingStarts().isEmpty());assertTrue(e.stockState().reservations().isEmpty());assertEquals(100,e.quantity("wood"));
            assertEquals(Status.BLOCKED,e.lifecycle.find(cmd.id()).orElseThrow().status());assertEquals(0,e.scalar("SELECT COUNT(*) FROM production_batches"));EconomySchema.validateExisting(e.connection);
        }
    }
    @Test void restartFinishesPendingSettlementWithoutApplyingSellerPmOrAdvancingOfflineTime()throws Exception{
        UUID operation,building;
        try(var e=new TestEconomy(file(),true)){
            building=e.id;e.production.startBatch(e.id);e.ticks(13);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");e.staffing.requestTarget(e.id,"laborer",5);
            var cmd=command(e,Kind.SALE);operation=cmd.id();e.inventory.failSettlement=true;
            assertThrows(IllegalStateException.class,()->e.lifecycle.request(cmd));assertEquals(Status.QUIESCING,e.lifecycle.find(operation).orElseThrow().status());
            assertEquals(ProductionBatch.Status.SETTLING_ABORTED,e.execution().batch().getStatus());assertEquals(10,e.staffingRepository.load(e.id).employed().get("laborer"));
        }
        try(var e=new TestEconomy(file(),false)){
            assertEquals(building,e.id);assertEquals(13,e.clock.elapsedTicks());assertEquals(Status.BLOCKED,e.lifecycle.find(operation).orElseThrow().status());assertNull(e.execution().batch());
            assertEquals("crude_tools",e.methods().effective().methods().get("tooling_workshop_base"));assertTrue(e.methods().pending().isPresent());assertEquals(5,e.staffingRepository.load(e.id).pending().get("laborer"));
            assertEquals(100,e.quantity("wood"));assertEquals(0,e.quantity("tools"));e.ticks(1);assertEquals(14,e.clock.elapsedTicks());
        }
    }
}
