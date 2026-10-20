package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconfigurationTransactionSafetyTest {
    @TempDir Path dir;Path file(){return dir.resolve("reconfiguration.sqlite");}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownReconfigurationCommitRetiresConnectionAndVerifiesTheWholeGraphBeforeRecovery(boolean committed)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");e.ticks(199);
            var cached=SqliteConnectionGuard.protect(e.connection).createStatement();
            e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_method_configurations")){e.faults.beforeCommit=!committed;e.faults.afterCommit=committed;}};
            assertThrows(IllegalStateException.class,()->e.ticks(1));assertThrows(CommitOutcomeUnknownException.class,()->e.methods());assertThrows(SQLException.class,()->cached.executeQuery("SELECT 1"));cached.close();
            try(var fresh=SqliteEconomyDatabase.open(file())){
                var methods=new SqliteProductionMethodConfigurationRepository(fresh,e.resolver,new Object());var execution=new SqliteProductionExecutionRepository(fresh,new Object()).load(e.id);
                assertEquals(committed?"reduced_tools":"crude_tools",methods.load(e.id).effective().methods().get("tooling_workshop_base"));
                assertEquals(committed?ProductionExecution.Boundary.NONE:ProductionExecution.Boundary.RECONFIGURING,execution.boundary());
                assertEquals(200L,execution.processingTick());
                try(var s=fresh.createStatement();var r=s.executeQuery("SELECT (SELECT SUM(employed) FROM building_staffing_roles),(SELECT employees FROM payroll_accruals)")){r.next();assertEquals(committed?7:14,r.getLong(1));assertEquals(r.getLong(1),r.getLong(2));}
            }
        }
        try(var e=new TestEconomy(file(),false)){assertEquals(30,e.quantity("tools"));assertEquals(0,e.execution().batch().getProgressUnits());assertEquals(7,e.scalar("SELECT employees FROM payroll_accruals"));assertTrue(e.staffingRepository.load(e.id).pending().isEmpty());assertEquals(200,e.execution().lastCompletedTick());}
    }
    @Test void failedRollbackNeverCommitsPartialPmStaffingOrWageBase()throws Exception{
        try(var e=new TestEconomy(file(),true);var reader=SqliteConnectionGuard.protect(DriverManager.getConnection("jdbc:sqlite:"+file()))){
            e.production.startBatch(e.id);e.production.selectProductionMethod(e.id,"tooling_workshop_base","reduced_tools");e.ticks(199);
            e.sql("CREATE TRIGGER fail_history BEFORE INSERT ON payroll_employment_history BEGIN SELECT RAISE(ABORT,'failed'); END");
            e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE production_method_configurations")){e.faults.rollbackFailure=true;e.faults.closeFailure=true;e.faults.commits=0;e.faults.resets=0;}};
            assertThrows(IllegalStateException.class,()->e.ticks(1));assertEquals(0,e.faults.commits);assertEquals(0,e.faults.resets);
            try(var s=reader.createStatement();var r=s.executeQuery("SELECT (SELECT SUM(employed) FROM building_staffing_roles),(SELECT employees FROM payroll_accruals),(SELECT boundary FROM production_executions)")){r.next();assertEquals(14,r.getLong(1));assertEquals(14,r.getLong(2));assertEquals("RECONFIGURING",r.getString(3));}
            assertThrows(CommitOutcomeUnknownException.class,()->e.execution());
        }
        try(var raw=DriverManager.getConnection("jdbc:sqlite:"+file());var s=raw.createStatement()){s.execute("DROP TRIGGER fail_history");}
        try(var e=new TestEconomy(file(),false)){assertEquals(7,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(30,e.quantity("tools"));assertEquals(0,e.execution().batch().getProgressUnits());}
    }
    @Test void repositoryRejectsRewoundOrSkippedTickEvidence()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);e.ticks(2);var original=e.execution();
            for(long invalid:List.of(1L,4L)){
                var forged=ProductionExecution.rehydrate(original.buildingId(),original.batch(),original.lastBatch(),original.boundary(),original.automatic(),original.executionRevision(),invalid,null,original.pauseReasons());
                assertThrows(IllegalStateException.class,()->e.repository.save(forged,original.executionRevision()));
            }
            assertEquals(2,e.execution().lastCompletedTick());assertEquals(2,e.execution().batch().getProgressUnits());
        }
    }
}
