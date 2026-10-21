package com.bbmurloc.victoriaeconomics.gametest;

import com.bbmurloc.victoriaeconomics.common.blockentity.BuildingAnchorBlockEntity;
import com.bbmurloc.victoriaeconomics.common.registry.ModBlocks;
import com.bbmurloc.victoriaeconomics.server.*;
import com.bbmurloc.victoriaeconomics.server.building.domain.BuildingLifecycleOperation.*;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;

@GameTestHolder("victoria_economics_gametests")
@PrefixGameTestTemplate(false)
public final class EconomyAcceptanceGameTests {
    /** Actual server callbacks drive all time, production and wage payments; no manual clock advancement. */
    @GameTest(template="empty",timeoutTicks=2700)
    public static void commandsLifecycleRestartUnloadedAnchorAndRealPayroll(GameTestHelper helper)throws Exception{
        var server=helper.getLevel().getServer();helper.assertTrue(ServerEconomyRuntime.isStarted(server),"NeoForge did not start the formal economy context");
        var life=new GameEconomyFixture(server);var sale=life.create(10,4);var demolition=life.create(10,4);
        var paid=new GameEconomyFixture(server);paid.fund(100000);var continuous=paid.create(10,4);
        var late=new GameEconomyFixture(server);var slow=late.create(1,1);
        try {life.command("ve production "+sale.getId()+" start",2);helper.fail("Permission level 2 could mutate production");}catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){}
        helper.assertTrue(life.command("ve production "+sale.getId()+" status",2)==1,"Read permission failed");
        var local=new BlockPos(1,1,1);helper.setBlock(local,ModBlocks.BUILDING_ANCHOR_BLOCK.get());var abs=helper.absolutePos(local);
        helper.assertTrue(life.command("ve debug "+sale.getId()+" bind_anchor "+abs.getX()+" "+abs.getY()+" "+abs.getZ(),4)==1,"Actual anchor command failed");
        BuildingAnchorBlockEntity anchor=helper.getBlockEntity(local);var tag=anchor.saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.assertTrue(tag.hasUUID("building_id")&&tag.getUUID("building_id").equals(sale.getId()),"Anchor NBT lost identity");
        var reloaded=new BuildingAnchorBlockEntity(abs,anchor.getBlockState());reloaded.loadWithComponents(tag,helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.getBuildingId().equals(sale.getId()),"NBT reload changed identity");
        try {reloaded.setBuildingId(demolition.getId());helper.fail("Anchor identity could be reused");}catch(IllegalStateException expected){}
        helper.destroyBlock(local);helper.assertTrue(life.context().getBuildingRegistry().contains(sale.getId()),"Physical block destruction deleted economic identity");
        var far=new BlockPos(100000,100,100000);helper.getLevel().getChunkAt(far);helper.getLevel().setBlock(far,ModBlocks.BUILDING_ANCHOR_BLOCK.get().defaultBlockState(),3);
        helper.assertTrue(paid.command("ve debug "+continuous.getId()+" bind_anchor "+far.getX()+" "+far.getY()+" "+far.getZ(),4)==1,"Far anchor binding failed");
        for(var b:List.of(sale,demolition,continuous,slow))helper.assertTrue(life.command("ve production "+b.getId()+" start",4)==1,"Actual start failed");
        long began=life.context().getEconomicTime().acceptedTick(),targetPeriod=began/1200+2;
        UUID saleOperation=UUID.randomUUID(),demolitionOperation=UUID.randomUUID(),slowBatch=life.context().getProductionService().execution(slow.getId()).batch().getId();
        AtomicBoolean abortChecked=new AtomicBoolean(),arrearsChecked=new AtomicBoolean(),finished=new AtomicBoolean();var pausedProgress=new AtomicLong(-1);
        AcceptanceMetrics.begin();
        helper.onEachTick(()->{
            if(finished.get())return;
            try{
                var ctx=ServerEconomyRuntime.get(server);long accepted=ctx.getEconomicTime().acceptedTick(),elapsed=accepted-began;
                if(elapsed>=10&&abortChecked.compareAndSet(false,true)){
                    try(var probe=life.connect();var sql=probe.createStatement()){
                        sql.execute("CREATE TRIGGER acceptance_fail_settlement BEFORE UPDATE ON inventory_locations WHEN NEW.location_id='"+sale.getId()+"' BEGIN SELECT RAISE(ABORT,'isolated inventory fault'); END");
                        helper.assertTrue(life.command("ve building "+sale.getId()+" lifecycle sale "+saleOperation+" "+life.company+" "+life.buyer+" 0",4)==0,"Injected settlement failure claimed success");
                        helper.assertTrue(ctx.getLifecycleService().find(saleOperation).orElseThrow().status()==Status.QUIESCING,"Failed settlement lost intent");
                        sql.execute("DROP TRIGGER acceptance_fail_settlement");
                    }
                    long beforeRestart=ctx.getEconomicTime().acceptedTick();ServerEconomyRuntime.stop(server);ServerEconomyRuntime.start(server);ctx=ServerEconomyRuntime.get(server);
                    helper.assertTrue(ctx.getEconomicTime().acceptedTick()==beforeRestart,"Context restart added offline time");
                    helper.assertTrue(ctx.getLifecycleService().find(saleOperation).orElseThrow().status()==Status.BLOCKED,"Restart did not recover original operation");
                    helper.assertTrue(life.command("ve building "+demolition.getId()+" lifecycle demolish "+demolitionOperation+" "+life.company+" 0",4)==1,"Demolition preparation command failed");
                    helper.assertTrue(life.command("ve building "+sale.getId()+" lifecycle sale "+saleOperation+" "+life.company+" "+life.buyer+" 0",4)==1,"Identical command replay failed");
                    helper.assertTrue(life.command("ve building "+sale.getId()+" lifecycle demolish "+saleOperation+" "+life.company+" 0",4)==0,"Conflicting operation replay was accepted");
                    for(var b:List.of(sale,demolition)){
                        var state=ctx.getInventoryStore().inspect(b.getId());helper.assertTrue(state.reservations().isEmpty()&&state.quantities().get("wood")==100000L&&state.quantities().getOrDefault("tools",0L)==0,"Lifecycle did not fully refund");
                        helper.assertTrue(ctx.getProductionService().execution(b.getId()).lastBatch().getStatus()==ProductionBatch.Status.ABORTED,"Lifecycle did not retain abort");
                        helper.assertTrue(ctx.getBuildingRegistry().get(b.getId()).ownerCompanyId().equals(life.company),"Blocked operation transferred ownership");
                    }
                }
                if(elapsed>0&&elapsed<=1100){
                    var batch=ctx.getProductionService().execution(continuous.getId()).batch();
                    helper.assertTrue(batch!=null&&batch.getProgressUnits()==elapsed%200,"Continuous production inserted a gap or double advance");
                }
                if(accepted/1200>began/1200&&arrearsChecked.compareAndSet(false,true)){
                    var e=ctx.getProductionService().execution(slow.getId());helper.assertTrue(e.batch()!=null&&e.batch().getStatus()==ProductionBatch.Status.PAUSED&&e.batch().getId().equals(slowBatch),"Real arrears did not pause original Batch");
                    helper.assertTrue(ctx.getPayrollQuery().state(slow.getId())==BuildingPayrollPort.State.ARREARS,"Unfunded wages were not persistent arrears");
                    helper.assertTrue(!ctx.getInventoryStore().inspect(slow.getId()).reservations().isEmpty(),"Arrears released original inputs");
                    pausedProgress.set(e.batch().getProgressUnits());ctx.getProductionService().setManualPause(slow.getId(),true);late.fund(10000);
                }
                if(accepted/1200>=targetPeriod&&finished.compareAndSet(false,true)){
                    var e=ctx.getProductionService().execution(slow.getId());helper.assertTrue(ctx.getPayrollQuery().state(slow.getId())==BuildingPayrollPort.State.CURRENT,"Real payment did not clear arrears");
                    helper.assertTrue(e.batch().getId().equals(slowBatch)&&e.batch().getProgressUnits()==pausedProgress.get()&&e.pauseReasons().contains(ProductionExecution.PauseReason.MANUAL),"Payment erased manual pause or original progress");
                    helper.assertTrue(paid.scalar("SELECT balance FROM finance_accounts WHERE id='"+paid.householdCash+"'")>0&&late.scalar("SELECT balance FROM finance_accounts WHERE id='"+late.householdCash+"'")>0,"Wages did not reach actual regional cash");
                    helper.assertTrue(!helper.getLevel().getChunkSource().hasChunk(far.getX()>>4,far.getZ()>>4),"Far anchor chunk did not unload; unload acceptance cannot be claimed");
                    ctx.getProductionService().setAutomatic(slow.getId(),false);ctx.getProductionService().setManualPause(slow.getId(),false);
                    helper.runAfterDelay(2,()->{
                        try{
                            var current=ServerEconomyRuntime.get(server);var resumed=current.getProductionService().execution(slow.getId()).batch();
                            helper.assertTrue(resumed.getId().equals(slowBatch)&&resumed.getProgressUnits()>pausedProgress.get(),"Real clearance did not resume original Batch");
                            try(var c=life.connect();var sql=c.createStatement()){
                                EconomySchema.validateExisting(c);String settings;
                                try(var r=sql.executeQuery("PRAGMA journal_mode")){r.next();settings="journal_mode="+r.getString(1);}
                                try(var r=sql.executeQuery("PRAGMA synchronous")){r.next();settings+=",synchronous="+r.getInt(1);}
                                AcceptanceMetrics.finish(Path.of("acceptance-performance.json"),current.getEconomicTime().acceptedTick(),settings);
                            }
                            helper.succeed();
                        }catch(Exception failure){throw new RuntimeException(failure);}
                    });
                }
            }catch(Exception failure){throw new RuntimeException(failure);}
        });
    }
}
