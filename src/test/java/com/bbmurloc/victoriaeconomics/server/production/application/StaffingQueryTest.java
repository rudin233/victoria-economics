package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteStaffingQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StaffingQueryTest {
    @TempDir Path dir;
    StaffingSnapshot query(TestEconomy e){return new StaffingCalculator(new SqliteStaffingQuery(e.connection,e.resolver,e.occupations,e.lock)).calculate(e.id);}
    @Test void queryReportsRealEmployedCapacityAndDerivedSupply()throws Exception{
        try(var e=new TestEconomy(dir.resolve("facts.sqlite"),true)){var role=query(e).occupations().get("laborer");assertEquals(10,role.employed());assertEquals(10,role.positionCapacity());assertEquals(100,role.totalSupply().orElseThrow());assertEquals(10,role.occupied());assertEquals(90,role.available().orElseThrow());}
    }
    @Test void pendingAbsenceZeroOverwriteAndCancelSurviveRestartWithoutChangingBatch()throws Exception{
        var file=dir.resolve("pending.sqlite");
        try(var e=new TestEconomy(file,true)){e.production.startBatch(e.id);e.staffing.requestTarget(e.id,"laborer",4);e.staffing.requestTarget(e.id,"laborer",0);assertEquals(0,query(e).occupations().get("laborer").pending().orElseThrow());}
        try(var e=new TestEconomy(file,false)){assertEquals(10,query(e).occupations().get("laborer").employed());assertEquals(0,query(e).occupations().get("laborer").pending().orElseThrow());e.staffing.cancelPending(e.id,"laborer");assertTrue(query(e).occupations().get("laborer").pending().isEmpty());}
    }
    @Test void pendingCannotBypassMinimumStaffingOrInventSupply()throws Exception{
        try(var e=new TestEconomy(dir.resolve("minimum.sqlite"),true)){e.staffing.requestTarget(e.id,"machinist",0);assertThrows(ProductionStartBlocked.class,()->e.production.startBatch(e.id));assertEquals(0,query(e).occupations().get("machinist").employed());e.setSupply("machinist",0);e.staffing.requestTarget(e.id,"machinist",4);assertTrue(e.staffingRepository.load(e.id).pending().isEmpty());assertFalse(e.staffingRepository.load(e.id).employed().containsKey("machinist"));}
    }
    @Test void threeFifthsBatchKeepsImmutableCountsAndCompletesAt334DespitePendingTargets()throws Exception{
        try(var e=new TestEconomy(dir.resolve("speed.sqlite"),true)){e.staffing.requestTarget(e.id,"laborer",6);e.staffing.requestTarget(e.id,"machinist",3);var batch=e.production.startBatch(e.id);var frozen=batch.getConfiguration();e.staffing.requestTarget(e.id,"laborer",10);
            e.ticks(333);assertEquals(999,e.execution().batch().getProgressUnits());assertEquals(frozen,e.execution().batch().getConfiguration());assertThrows(UnsupportedOperationException.class,()->frozen.workforcePlan().participatingWorkers().clear());
            e.ticks(1);assertEquals(batch.getId(),e.execution().lastBatch().getId());assertEquals(0,e.execution().batch().getProgressUnits());assertEquals(10,e.staffingRepository.load(e.id).employed().get("laborer"));
        }
    }
}
