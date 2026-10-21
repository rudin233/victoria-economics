package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.building.*;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingLifecycleCompatibilityTest {
    @TempDir Path dir;Path file(){return dir.resolve("format.sqlite");}
    @ParameterizedTest @ValueSource(strings={"automatic","source","future","missingPolicy","unknownPolicy","wrongReceipt","activeBatch","incompleteHandoff"})
    void forgedLifecycleFactsAreRejectedWithoutRewritingMainOrWal(String mutation)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            e.production.startBatch(e.id);var op=e.lifecycle.request(BuildingLifecycleTest.command(e,Kind.SALE));
            e.sql("PRAGMA journal_mode=WAL");e.sql("PRAGMA wal_autocheckpoint=0");
            switch(mutation){
                case "automatic"->e.sql("UPDATE production_executions SET automatic=1");
                case "source"->e.sql("UPDATE economic_buildings SET owner_company_id='"+BuildingLifecycleTest.BUYER+"'");
                case "future"->e.sql("UPDATE building_lifecycle_operations SET blocked_tick=999");
                case "missingPolicy"->e.sql("UPDATE building_lifecycle_operations SET blockers='SALE_CONSIDERATION_UNDECIDED'");
                case "unknownPolicy"->e.sql("UPDATE building_lifecycle_operations SET blockers='FAKE_APPROVED'");
                case "wrongReceipt"->e.sql("UPDATE inventory_locations SET state_json=replace(state_json,'ABORTED','COMPLETED')");
                case "activeBatch"->{e.sql("UPDATE building_lifecycle_operations SET status='QUIESCING',blocked_tick=NULL,blockers='',revision=0");e.sql("UPDATE production_executions SET current_batch_id=last_batch_id,last_batch_id=NULL,boundary='NONE'");e.sql("UPDATE production_batches SET status='ACTIVE'");}
                case "incompleteHandoff"->e.sql("UPDATE production_executions SET current_batch_id=last_batch_id,last_batch_id=NULL,boundary='RECONFIGURING'");
            }
            byte[] main=Files.readAllBytes(file()),wal=Files.readAllBytes(Path.of(file()+"-wal"));
            assertThrows(IllegalStateException.class,()->SqliteEconomyDatabase.open(file()));
            assertArrayEquals(main,Files.readAllBytes(file()));assertArrayEquals(wal,Files.readAllBytes(Path.of(file()+"-wal")));
        }
    }
    @Test void validBlockedGraphReloadsWithoutRepairOrChangedBytes()throws Exception{
        UUID operation;
        try(var e=new TestEconomy(file(),true)){e.production.startBatch(e.id);var op=e.lifecycle.request(BuildingLifecycleTest.command(e,Kind.DEMOLITION));operation=op.command().id();}
        byte[] main=Files.readAllBytes(file());
        try(var c=SqliteEconomyDatabase.open(file())){assertEquals(Status.BLOCKED,new com.bbmurloc.victoriaeconomics.server.building.infrastructure.SqliteBuildingLifecycleOperationRepository(c,new Object()).find(operation).orElseThrow().status());}
        assertArrayEquals(main,Files.readAllBytes(file()));
    }
    /** This historical format fixture is not a formal demolition path or approval of the unresolved closeout rules. */
    static void historicalTombstoneFixture(TestEconomy e)throws Exception{
        e.staffing.requestTarget(e.id,"laborer",0);e.staffing.requestTarget(e.id,"machinist",0);
        e.stock.withdraw(e.id,Map.of("wood",100L,"iron",100L));
        long tick=e.clock.elapsedTicks();
        SqliteTransactions.write(e.connection,tx->{
            var p=e.payrollRepository.inTransaction(tx);p.close(p.currentForBuilding(e.id).id(),tick);
            e.sql("UPDATE economic_buildings SET lifecycle='DEMOLISHED',identity_revision=identity_revision+1 WHERE id='"+e.id+"'");return null;
        });
    }
    @Test void historicalTombstoneHasNoActivePayrollButKeepsIdentityAndDoesNotStopWorldTime()throws Exception{
        UUID id;
        try(var e=new TestEconomy(file(),true)){
            id=e.id;historicalTombstoneFixture(e);EconomySchema.validateExisting(e.connection);
            e.ticks(3);assertEquals(3,e.clock.elapsedTicks());assertEquals(0,e.scalar("SELECT COUNT(*) FROM payroll_accruals WHERE closed_tick IS NULL"));
            assertEquals(1,e.scalar("SELECT COUNT(*) FROM payroll_accruals"));assertEquals(BuildingLifecycle.DEMOLISHED,e.buildingRepository.load(id).lifecycle());
            assertThrows(IllegalStateException.class,()->e.production.startBatch(id));assertThrows(IllegalStateException.class,()->e.staffing.requestTarget(id,"laborer",1));
            assertThrows(IllegalStateException.class,()->e.buildingService.createBuilding(id,"tooling_workshop",com.bbmurloc.victoriaeconomics.server.TestIdentities.COMPANY,com.bbmurloc.victoriaeconomics.server.TestIdentities.REGION));
            assertEquals(1,e.scalar("SELECT COUNT(*) FROM economic_buildings"));assertThrows(UnsupportedOperationException.class,()->e.buildingRepository.delete(id));
            EconomySchema.validateExisting(e.connection);
        }
        try(var e=new TestEconomy(file(),false)){assertEquals(BuildingLifecycle.DEMOLISHED,e.buildingRepository.load(id).lifecycle());assertEquals(3,e.clock.elapsedTicks());e.ticks(1);assertEquals(4,e.clock.elapsedTicks());}
    }
    @ParameterizedTest @ValueSource(strings={"activePayroll","employees","inventory","automatic","start"})
    void inconsistentTombstonesFailBeforeAnyBusinessWrite(String problem)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            historicalTombstoneFixture(e);
            switch(problem){
                case "activePayroll"->e.sql("UPDATE payroll_accruals SET closed_tick=NULL");
                case "employees"->e.sql("INSERT INTO building_staffing_roles VALUES('"+e.id+"','laborer',1,NULL)");
                case "inventory"->e.stock.deposit(e.id,Map.of("tools",1L));
                case "automatic"->e.sql("UPDATE production_executions SET automatic=1");
                case "start"->e.sql("INSERT INTO production_start_intents VALUES('"+UUID.randomUUID()+"','"+e.id+"')");
            }
        }
        byte[] before=Files.readAllBytes(file());assertThrows(IllegalStateException.class,()->SqliteEconomyDatabase.open(file()));assertArrayEquals(before,Files.readAllBytes(file()));
    }
}
