package com.bbmurloc.victoriaeconomics.server.production.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.RegionalWageRate;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkforceContractTest {
    @TempDir Path dir;
    @Test void productionUsesOnlyPersistentIntegerStaffingWithNoEmploymentTable()throws Exception{
        try(var e=new TestEconomy(dir.resolve("authority.sqlite"),true)){
            e.staffing.requestTarget(e.id,"laborer",6);e.staffing.requestTarget(e.id,"machinist",3);
            var frozen=e.production.startBatch(e.id).getConfiguration();assertEquals(Map.of("laborer",6,"machinist",3),frozen.workforcePlan().participatingWorkers());assertEquals(0.6,frozen.productionSpeed());
            assertEquals(0,e.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name='employment_state'"));
        }
    }
    @Test void pendingDoesNotChangeEffectiveHeadcountOrFrozenParticipation()throws Exception{
        try(var e=new TestEconomy(dir.resolve("pending.sqlite"),true)){
            var frozen=e.production.startBatch(e.id).getConfiguration();e.staffing.requestTarget(e.id,"laborer",3);
            assertEquals(10,e.staffingRepository.load(e.id).employed().get("laborer"));assertEquals(3,e.staffingRepository.load(e.id).pending().get("laborer"));
            assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(frozen,e.execution().batch().getConfiguration());
            assertThrows(IllegalStateException.class,()->e.staffing.applyEffectiveChanges(e.id));
        }
    }
    @Test void newEmployeesAfterReservationAreRecheckedAndFailedStartCompensates()throws Exception{
        try(var e=new TestEconomy(dir.resolve("recheck.sqlite"),true)){
            e.inventory.afterReserve=()->e.staffingRaceFixture("machinist",0);
            assertThrows(ProductionStartBlocked.class,()->e.production.startBatch(e.id));assertNull(e.execution().batch());assertTrue(e.stockState().reservations().isEmpty());assertTrue(e.journal.pendingStarts().isEmpty());
        }
    }
    @Test void liveWageRateAndPendingRequestsCannotRewriteAnExistingBatchSnapshot()throws Exception{
        try(var e=new TestEconomy(dir.resolve("wage-snapshot.sqlite"),true)){
            var batch=e.production.startBatch(e.id);var frozen=batch.getConfiguration();e.ticks(50);
            e.accrual.configureRate(new RegionalWageRate(TestIdentities.REGION,new CurrencyId(new UUID(700,1)),50,2,1));
            e.staffing.requestTarget(e.id,"laborer",3);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");
            e.ticks(1);
            assertEquals(batch.getId(),e.execution().batch().getId());assertEquals(frozen,e.execution().batch().getConfiguration());
            assertEquals(Map.of("laborer",10,"machinist",4),frozen.workforcePlan().participatingWorkers());assertEquals(51,e.execution().batch().getProgressUnits());
            assertEquals(728,e.scalar("SELECT remainder FROM payroll_accruals"));assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals"));
        }
    }
}
