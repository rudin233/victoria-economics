package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import com.bbmurloc.victoriaeconomics.server.building.infrastructure.SqliteBuildingLifecycleOperationRepository;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingLifecycleRecoveryTest {
    @TempDir Path dir;Path file(){return dir.resolve("recovery.sqlite");}
    @ParameterizedTest @ValueSource(strings={"intent","abort","handoff","blocked"})
    void eachLifecycleWriteFailureRollsBackItsWholePhysicalStage(String point)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var batch=e.production.startBatch(e.id);e.ticks(3);var cmd=BuildingLifecycleTest.command(e,Kind.SALE);
            String trigger=switch(point){
                case "intent"->"CREATE TRIGGER fail BEFORE INSERT ON building_lifecycle_operations BEGIN SELECT RAISE(ABORT,'intent'); END";
                case "abort"->"CREATE TRIGGER fail BEFORE UPDATE ON production_batches WHEN NEW.status='SETTLING_ABORTED' BEGIN SELECT RAISE(ABORT,'abort'); END";
                case "handoff"->"CREATE TRIGGER fail BEFORE UPDATE ON production_executions WHEN OLD.boundary='RECONFIGURING' AND NEW.boundary='NONE' BEGIN SELECT RAISE(ABORT,'handoff'); END";
                default->"CREATE TRIGGER fail BEFORE UPDATE ON building_lifecycle_operations BEGIN SELECT RAISE(ABORT,'blocked'); END";
            };e.sql(trigger);assertThrows(IllegalStateException.class,()->e.lifecycle.request(cmd));
            boolean started=point.equals("handoff")||point.equals("blocked");
            assertEquals(started?1:0,e.scalar("SELECT COUNT(*) FROM building_lifecycle_operations"));
            assertEquals(started?ProductionBatch.Status.ABORTED:ProductionBatch.Status.ACTIVE,e.execution().batch().getStatus());
            assertEquals(started?ProductionExecution.Boundary.RECONFIGURING:ProductionExecution.Boundary.NONE,e.execution().boundary());
            assertEquals(!started,e.execution().automatic());assertEquals(100,e.quantity("wood"));assertEquals(started?0:1,e.stockState().reservations().size());
            assertEquals(14,e.scalar("SELECT employees FROM payroll_accruals"));assertEquals(14,e.scalar("SELECT SUM(employed) FROM building_staffing_roles"));
            e.sql("DROP TRIGGER fail");var complete=e.lifecycle.request(cmd);assertEquals(Status.BLOCKED,complete.status());assertEquals(batch.getId(),e.execution().lastBatch().getId());assertEquals(1,e.stockState().settlements().size());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownStartCommitNeverPublishesOrRepeatsAnUnverifiedIntent(boolean committed)throws Exception{
        Command command;
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);command=BuildingLifecycleTest.command(e,Kind.SALE);
            var derived=SqliteConnectionGuard.protect(e.connection).createStatement();
            e.faults.beforeSql=sql->{if(sql.startsWith("INSERT INTO building_lifecycle_operations")){e.faults.beforeCommit=!committed;e.faults.afterCommit=committed;}};
            assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.request(command));
            assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.request(command));assertThrows(CommitOutcomeUnknownException.class,()->e.execution());assertThrows(SQLException.class,()->derived.executeQuery("SELECT 1"));derived.close();
            var verified=e.lifecycle.verify(command);assertEquals(committed,verified.isPresent());if(committed)assertEquals(Status.QUIESCING,verified.orElseThrow().status());
            try(var fresh=SqliteEconomyDatabase.open(file())) {
                var execution=new com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository(fresh,new Object()).load(e.id);
                assertEquals(committed?ProductionBatch.Status.SETTLING_ABORTED:ProductionBatch.Status.ACTIVE,execution.batch().getStatus());
            }
        }
        try(var e=new TestEconomy(file(),false)){
            var op=e.lifecycle.request(command);assertEquals(Status.BLOCKED,op.status());assertEquals(1,e.stockState().settlements().size());assertEquals(100,e.quantity("wood"));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownHandoffCommitsBarrierAndBlockedFactTogether(boolean committed)throws Exception{
        Command command;
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);command=BuildingLifecycleTest.command(e,Kind.DEMOLITION);
            e.faults.beforeSql=sql->{if(sql.startsWith("UPDATE building_lifecycle_operations")){e.faults.beforeCommit=!committed;e.faults.afterCommit=committed;}};
            assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.request(command));
            var verified=e.lifecycle.verify(command).orElseThrow();assertEquals(committed?Status.BLOCKED:Status.QUIESCING,verified.status());
            try(var fresh=SqliteEconomyDatabase.open(file())) {
                var execution=new com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository(fresh,new Object()).load(e.id);
                assertEquals(committed?ProductionExecution.Boundary.NONE:ProductionExecution.Boundary.RECONFIGURING,execution.boundary());
                assertEquals(1,new com.bbmurloc.victoriaeconomics.server.inventory.infrastructure.SqliteInventoryStore(fresh,new Object()).inspect(e.id).settlements().size());
            }
        }
        try(var e=new TestEconomy(file(),false)){assertEquals(Status.BLOCKED,e.lifecycle.find(command.id()).orElseThrow().status());assertEquals(100,e.quantity("wood"));assertEquals(0,e.quantity("tools"));assertTrue(e.stockState().reservations().isEmpty());}
    }
    @Test void inventoryReceiptLossRetainsIntentAndVerifiesDurableRefundBeforeRetry()throws Exception{
        Command command;
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);command=BuildingLifecycleTest.command(e,Kind.DEMOLITION);e.inventory.loseSettlementAcknowledgement=true;
            assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.request(command));assertThrows(CommitOutcomeUnknownException.class,()->e.execution());
            assertEquals(Status.QUIESCING,e.lifecycle.verify(command).orElseThrow().status());
        }
        try(var e=new TestEconomy(file(),false)){assertEquals(Status.BLOCKED,e.lifecycle.find(command.id()).orElseThrow().status());assertEquals(100,e.quantity("wood"));assertEquals(1,e.stockState().settlements().size());assertEquals(0,e.quantity("tools"));}
    }
    @Test void rollbackAndCloseFailureCannotFinallyCommitIntentOrAbort()throws Exception{
        try(var e=new TestEconomy(file(),true);var observer=DriverManager.getConnection("jdbc:sqlite:"+file())){
            e.production.startBatch(e.id);var command=BuildingLifecycleTest.command(e,Kind.SALE);
            e.sql("CREATE TRIGGER fail BEFORE UPDATE ON production_executions BEGIN SELECT RAISE(ABORT,'execution'); END");
            e.faults.beforeSql=sql->{if(sql.startsWith("INSERT INTO building_lifecycle_operations")){e.faults.rollbackFailure=true;e.faults.closeFailure=true;e.faults.commits=0;e.faults.resets=0;}};
            assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.request(command));assertEquals(0,e.faults.commits);assertEquals(0,e.faults.resets);
            try(var s=observer.createStatement();var r=s.executeQuery("SELECT (SELECT COUNT(*) FROM building_lifecycle_operations),(SELECT automatic FROM production_executions),(SELECT status FROM production_batches)")) {r.next();assertEquals(0,r.getLong(1));assertEquals(1,r.getLong(2));assertEquals("ACTIVE",r.getString(3));}
            assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.forBuilding(e.id));
        }
    }
    @Test void postCommitCleanupFailureRequiresNewConnectionVerification()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);var command=BuildingLifecycleTest.command(e,Kind.SALE);
            e.faults.beforeSql=sql->{if(sql.startsWith("INSERT INTO building_lifecycle_operations"))e.faults.resetFailure=true;};
            assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.request(command));assertEquals(Status.QUIESCING,e.lifecycle.verify(command).orElseThrow().status());
            assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.forBuilding(e.id));
        }
    }
    @Test void committedQueriesCannotReadUnresolvedLocalIntentAndCaughtRepositoryFailurePoisonsOuterWrite()throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var cmd=BuildingLifecycleTest.command(e,Kind.DEMOLITION);e.lifecycle.request(cmd);
            e.connection.setAutoCommit(false);try {assertThrows(CommitOutcomeUnknownException.class,()->e.lifecycle.find(cmd.id()));assertFalse(e.connection.getAutoCommit());}finally{e.connection.rollback();e.connection.setAutoCommit(true);}
            var m=e.methods();long rev=m.configurationRevision();m.requestSelection("tooling_workshop_base","reduced_tools",true);
            assertThrows(IllegalStateException.class,()->SqliteTransactions.write(e.connection,tx->{
                e.sql("UPDATE regional_labor_supply SET total_supply=123");
                try{e.configurations.inTransaction(tx).save(m,rev);}catch(IllegalStateException expected){}
                return null;
            }));assertEquals(100,e.scalar("SELECT total_supply FROM regional_labor_supply WHERE occupation_id='laborer'"));assertEquals(rev,e.methods().configurationRevision());
        }
    }
}
