package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PayrollPaymentPauseIntegrationTest {
    @TempDir Path dir;Path file(){return dir.resolve("wage-production.sqlite");}
    void fund(UUID account,long amount)throws Exception{try(var fixture=new FinancialTestWorld(file())){fixture.fundingFixture(account,amount);}}
    @Test void realPartialWagesPauseThenFullNextPeriodPaymentResumesSameBatchButPreservesManualReason()throws Exception{
        UUID primary,first,second,secondBuilding;ProductionBatchConfiguration frozen;long progress;
        try(var e=new TestEconomy(file(),true)){
            primary=e.id;
            e.staffing.requestTarget(e.id,"laborer",1);e.staffing.requestTarget(e.id,"machinist",1);
            secondBuilding=e.createBuilding().getId();e.stock.createLocation(secondBuilding,1000);e.stock.deposit(secondBuilding,Map.of("wood",100L,"iron",100L));e.staffing.requestTarget(secondBuilding,"laborer",1);e.staffing.requestTarget(secondBuilding,"machinist",1);
            first=e.production.startBatch(e.id).getId();frozen=e.execution().batch().getConfiguration();second=e.production.startBatch(secondBuilding).getId();
            fund(e.companyCash,2);e.ticks(1200);progress=e.execution().batch().getProgressUnits();assertEquals(1199,progress);
            assertEquals(State.ARREARS,e.payroll.state(e.id));assertEquals(ProductionBatch.Status.PAUSED,e.execution().batch().getStatus());assertEquals(2,e.accounts.load(e.householdCash).balance());assertEquals(2,e.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));
            assertEquals(1,e.stockState().reservations().size());assertEquals(first,e.execution().batch().getId());assertEquals(second,e.repository.load(secondBuilding).batch().getId());
            e.production.setAutomatic(e.id,false);e.production.setManualPause(secondBuilding,true);
        }
        // Restart uses the same accepted time/Run, without a second payment or offline catchup.
        try(var e=new TestEconomy(file(),false)){
            assertEquals(1200,e.clock.elapsedTicks());assertEquals(first,e.repository.load(primary).batch().getId());assertEquals(progress,e.repository.load(primary).batch().getProgressUnits());assertEquals(2,e.accounts.load(e.householdCash).balance());
            fund(e.companyCash,6);e.ticks(1200);
            assertEquals(State.CURRENT,e.payroll.state(primary));assertEquals(State.CURRENT,e.payroll.state(secondBuilding));assertEquals(0,e.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));assertEquals(8,e.accounts.load(e.householdCash).balance());
            var resumed=e.repository.load(primary);assertEquals(first,resumed.batch().getId());assertEquals(frozen,resumed.batch().getConfiguration());assertEquals(progress+1,resumed.batch().getProgressUnits());assertTrue(resumed.batch().isActive());assertFalse(resumed.automatic());assertEquals(1,e.stock.inspect(primary).reservations().size());
            var other=e.repository.load(secondBuilding);assertEquals(second,other.batch().getId());assertEquals(progress,other.batch().getProgressUnits());assertEquals(Set.of(ProductionExecution.PauseReason.MANUAL),other.pauseReasons());
            e.production.setManualPause(secondBuilding,false);e.ticks(1);assertEquals(second,e.repository.load(secondBuilding).batch().getId());assertEquals(progress+1,e.repository.load(secondBuilding).batch().getProgressUnits());assertEquals(2,e.scalar("SELECT COUNT(*) FROM payroll_payment_runs"));
        }
    }
    @Test void irrevocablyAbortedBatchNeverResurrectsWhenItsRealWagesArePaid()throws Exception{
        UUID batch;
        try(var e=new TestEconomy(file(),true)){
            batch=e.production.startBatch(e.id).getId();e.production.setManualPause(e.id,true);e.ticks(1200);assertEquals(State.ARREARS,e.payroll.state(e.id));e.production.abortBatch(e.id);
            assertNull(e.execution().batch());assertEquals(batch,e.execution().lastBatch().getId());assertTrue(e.execution().lastBatch().isAborted());assertTrue(e.stockState().reservations().isEmpty());
            // Explicit accepted-time fixture for an idle stopped actor, not production catchup or offline scheduling.
            try(var fixture=new FinancialTestWorld(file())){fixture.fundingFixture(e.companyCash,28);fixture.acceptedTimeFixture(2400);fixture.accrual.recoverAcceptedTime();}
            e.periods.recoverAcceptedTime();assertEquals(State.CURRENT,e.payroll.state(e.id));assertNull(e.execution().batch());assertEquals(batch,e.execution().lastBatch().getId());assertEquals(0,e.quantity("tools"));assertEquals(100,e.quantity("wood"));assertFalse(e.execution().automatic());
        }
        try(var e=new TestEconomy(file(),false)){assertNull(e.execution().batch());assertTrue(e.execution().lastBatch().isAborted());assertEquals(State.CURRENT,e.payroll.state(e.id));}
    }
}
