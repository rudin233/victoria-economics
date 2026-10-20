package com.bbmurloc.victoriaeconomics.server.production.application;

import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.infrastructure.SqliteProductionExecutionRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionSchemaContractTest {
    @TempDir Path dir;Path file(){return dir.resolve("contract.sqlite");}
    @Test void v7StoresOnlyIntegerParticipationAndReloadsWithoutRewritingBytes()throws Exception{
        UUID id;ProductionBatchConfiguration snapshot;
        try(var e=new TestEconomy(file(),true)){
            id=e.id;snapshot=e.production.startBatch(id).getConfiguration();
            try(var s=e.connection.createStatement();var r=s.executeQuery("SELECT configuration_json FROM production_batches")){r.next();String json=r.getString(1);assertTrue(json.contains("\"formatVersion\":7"));assertTrue(json.contains("\"participatingWorkers\""));assertFalse(json.contains("\"employees\""));}
            assertEquals(0,e.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name='employment_state'"));
        }
        byte[] before=Files.readAllBytes(file());try(var c=SqliteEconomyDatabase.open(file())){assertEquals(snapshot,new SqliteProductionExecutionRepository(c,new Object()).load(id).batch().getConfiguration());}assertArrayEquals(before,Files.readAllBytes(file()));
    }
    @ParameterizedTest @ValueSource(strings={"npcSnapshot","participationMismatch","payrollMismatch","staffingMismatch","pausedWithoutReasons","activeWithReasons","endedWithoutBarrier","activeWithBarrier","futureCompletedTick","futurePendingTick","unknownReason","duplicateReason"})
    void inconsistentProductionStaffingPayrollAndBarrierFactsAreRejectedWithoutRepair(String corruption)throws Exception{
        try(var e=new TestEconomy(file(),true)){
            var batch=e.production.startBatch(e.id);var original=batch.getConfiguration();
            switch(corruption){
                case "npcSnapshot"->{
                    String old=EconomyStateCodec.encodeBatchConfiguration(original).replace("\"participatingWorkers\"","\"employees\"");
                    old=old.replace("\"laborer\":10","\"laborer\":[\""+UUID.randomUUID()+"\"]");
                    try(var s=e.connection.prepareStatement("UPDATE production_batches SET configuration_json=?")){s.setString(1,old);s.executeUpdate();}
                }
                case "participationMismatch"->{
                    var different=new ProductionBatchConfiguration(original.productionMethodSelections(),original.resolvedRecipe(),new WorkforcePlan(Map.of("laborer",9,"machinist",4),9,10),original.effectiveRevision());
                    String validJson=EconomyStateCodec.encodeBatchConfiguration(different);assertEquals(different,EconomyStateCodec.decodeBatchConfiguration(validJson));
                    try(var s=e.connection.prepareStatement("UPDATE production_batches SET configuration_json=?")){s.setString(1,validJson);s.executeUpdate();}
                }
                case "payrollMismatch"->e.sql("UPDATE payroll_accruals SET employees=13");
                case "staffingMismatch"->e.sql("UPDATE building_staffing_roles SET employed=9 WHERE occupation_id='laborer'");
                case "pausedWithoutReasons"->e.sql("UPDATE production_batches SET status='PAUSED'");
                case "activeWithReasons"->e.sql("UPDATE production_executions SET pause_reasons_json='[\"MANUAL\"]'");
                case "endedWithoutBarrier"->e.sql("UPDATE production_batches SET status='ABORTED'");
                case "activeWithBarrier"->e.sql("UPDATE production_executions SET boundary='RECONFIGURING'");
                case "futureCompletedTick"->e.sql("UPDATE production_executions SET last_completed_tick=1");
                case "futurePendingTick"->e.sql("UPDATE production_executions SET processing_tick=1");
                case "unknownReason"->e.sql("UPDATE production_executions SET pause_reasons_json='[\"FUTURE\"]'");
                default->e.sql("UPDATE production_executions SET pause_reasons_json='[\"MANUAL\",\"MANUAL\"]'");
            }
        }
        byte[] before=Files.readAllBytes(file());assertThrows(IllegalStateException.class,()->{try(var ignored=SqliteEconomyDatabase.open(file())){fail("Inconsistent world opened");}});assertArrayEquals(before,Files.readAllBytes(file()));
    }
}
