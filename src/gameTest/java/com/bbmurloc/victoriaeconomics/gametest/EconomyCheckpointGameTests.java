package com.bbmurloc.victoriaeconomics.gametest;

import com.bbmurloc.victoriaeconomics.server.*;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.google.gson.GsonBuilder;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Actual native save lifecycle and deliberate process death in owned GameTest worlds only. */
@GameTestHolder("victoria_economics_checkpointtests")
@PrefixGameTestTemplate(false)
public final class EconomyCheckpointGameTests {
    private static long levelSaves;
    private static String mode(){return System.getProperty("ve.acceptanceMode");}
    static void levelSaved(net.neoforged.neoforge.event.level.LevelEvent.Save event){
        if(Set.of("checkpoint","checkpointFailure").contains(mode())&&event.getLevel() instanceof ServerLevel)levelSaves++;
    }
    @GameTest(template="empty",timeoutTicks=6700)
    public static void actualAutosaveManualFlushAndDimensionsShareOneEconomicCheckpoint(GameTestHelper helper)throws Exception{
        if(!"checkpoint".equals(mode())){helper.succeed();return;}
        var server=helper.getLevel().getServer();var f=new GameEconomyFixture(server);f.fund(10_000_000);
        var buildings=new ArrayList<com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding>();
        for(int i=0;i<4;i++)buildings.add(f.create(10,4));
        for(var b:buildings)f.context().getProductionService().startBatch(b.getId());
        long began=f.context().getEconomicTime().acceptedTick();
        var ordinary=new AtomicBoolean();var flush=new AtomicBoolean();var finished=new AtomicBoolean();
        PerformanceMeasurements.begin(6200);
        helper.onEachTick(()->{
            if(finished.get())return;
            try{
                var ctx=ServerEconomyRuntime.get(server);long tick=ctx.getEconomicTime().acceptedTick(),elapsed=tick-began;
                if(elapsed>=200&&ordinary.compareAndSet(false,true)){
                    long count=ctx.nativeSaveStatus().requests();
                    helper.assertTrue(f.command("save-all",4)==1,"Actual save-all failed");
                    helper.assertTrue(ctx.nativeSaveStatus().requests()==count+1,"Dimensions duplicated global snapshot capture");
                }
                if(elapsed>=400&&flush.compareAndSet(false,true)){
                    helper.assertTrue(f.command("save-all flush",4)==1,"Actual save-all flush failed");
                    helper.assertTrue(ctx.checkpointStatus().committed().economicTick()==tick,"flush returned before the requested VE checkpoint committed");
                    try(var c=SqliteEconomyDatabase.openReadOnly(f.database)){
                        helper.assertTrue(EconomyCheckpoint.readVerified(c).receipt().equals(ctx.checkpointStatus().committed()),"flush receipt disagrees with durable disk image");
                    }
                }
                if(!PerformanceMeasurements.done())return;
                finished.set(true);
                helper.assertTrue(ctx.nativeSaveStatus().automaticRequests()>=1,"Original 6000-Tick autosave was not observed");
                helper.assertTrue(ctx.nativeSaveStatus().requests()>=3&&ctx.nativeSaveStatus().flushRequests()>=1,"Native save lifecycle hooks missing");
                helper.assertTrue(levelSaves>=ctx.nativeSaveStatus().requests(),"Per-level save events did not occur");
                ctx.awaitEconomicSave();
                for(var b:buildings){var e=ctx.getProductionService().execution(b.getId());
                    helper.assertTrue(e.lastCompletedTick()==tick&&e.batch().getProgressUnits()==elapsed%200,"Saving repeated or lost production Tick");
                }
                ctx.flushEconomicSave();
                Files.writeString(Path.of("native-save-evidence.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("nativeSave",ctx.nativeSaveStatus(),"levelSaveEvents",levelSaves,"acceptedTick",tick,"checkpoint",ctx.checkpointStatus())));
                PerformanceMeasurements.write("native-save4",tick,"disk DELETE/FULL; runtime MEMORY",Files.size(f.database),ctx);
                helper.succeed();
            }catch(Exception failure){throw new IllegalStateException(failure);}
        });
    }

    @GameTest(template="empty",timeoutTicks=350)
    public static void processCrashDiscardsOnlyTheUnsavedWholeEconomicSuffix(GameTestHelper helper)throws Exception{
        if(!"crash".equals(mode())){helper.succeed();return;}
        var server=helper.getLevel().getServer();var f=new GameEconomyFixture(server);f.fund(1_000_000);
        var b=f.create(10,4);f.context().getProductionService().startBatch(b.getId());
        long began=f.context().getEconomicTime().acceptedTick();var saved=new AtomicBoolean();
        helper.onEachTick(()->{
            try{
                var ctx=ServerEconomyRuntime.get(server);long tick=ctx.getEconomicTime().acceptedTick();
                if(tick-began>=210&&saved.compareAndSet(false,true)){
                    server.saveEverything(true,true,true);
                    var receipt=ctx.checkpointStatus().committed();
                    Files.writeString(Path.of("crash-expected.json"),new GsonBuilder().create().toJson(receipt));
                }
                if(tick-began>=250){
                    var receipt=ctx.checkpointStatus().committed();
                    helper.assertTrue(receipt.economicTick()<tick,"No unsaved economic suffix to test");
                    Files.writeString(Path.of("crash-reached.txt"),"HALTING 73 at runtime tick "+tick+"; saved tick "+receipt.economicTick());
                    Runtime.getRuntime().halt(73); // no shutdown hooks, final checkpoint, or Context.close
                }
            }catch(Exception failure){throw new IllegalStateException(failure);}
        });
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void restartAfterProcessCrashLoadsExactlyOneCompleteRecoveryImage(GameTestHelper helper)throws Exception{
        if(!"crashRestart".equals(mode())){helper.succeed();return;}
        var expected=new com.google.gson.Gson().fromJson(Files.readString(Path.of("crash-expected.json")),EconomyCheckpoint.Receipt.class);
        var ctx=ServerEconomyRuntime.get(helper.getLevel().getServer());
        helper.assertTrue(ctx.checkpointStatus().committed().equals(expected),"Restored a different or mixed checkpoint");
        // First server ticks may already have occurred before GameTest discovery; startup hook checks the exact image.
        helper.assertTrue(CrashRecoveryEvidence.verified,"Exact pre-Tick crash recovery was not verified");
        ctx.flushEconomicSave();
        Files.writeString(Path.of("acceptance-result.txt"),"PASSED crashRestart");helper.succeed();
    }
    static final class CrashRecoveryEvidence {
        static boolean verified;
        static void afterContext(net.minecraft.server.MinecraftServer server){
            if(!"crashRestart".equals(mode()))return;
            try{
                var expected=new com.google.gson.Gson().fromJson(Files.readString(Path.of("crash-expected.json")),EconomyCheckpoint.Receipt.class);
                var ctx=ServerEconomyRuntime.get(server);
                if(ctx.getEconomicTime().acceptedTick()!=expected.economicTick())throw new IllegalStateException("Crash recovery invented time");
                try(var c=ctx.openRuntimeVerificationConnection()){
                    EconomyCheckpoint.requireMatches(expected,EconomyCheckpoint.capture(c));
                }
                verified=true;
                Files.writeString(Path.of("crash-recovered.txt"),"VERIFIED entire checkpoint "+expected.checkpointId()+" at tick "+expected.economicTick());
                Files.writeString(Path.of("crash-startup-performance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(PerformanceMeasurements.startupForEvidence()));
            }catch(Exception failure){throw new IllegalStateException(failure);}
        }
    }

    @GameTest(template="empty",timeoutTicks=150)
    public static void failedEconomicCaptureCannotBlockNativeFilesOrAcknowledgeAnOlderSave(GameTestHelper helper)throws Exception{
        if(!"checkpointFailure".equals(mode())){helper.succeed();return;}
        var server=helper.getLevel().getServer();var ctx=ServerEconomyRuntime.get(server);
        long requests=ctx.nativeSaveStatus().requests();
        for(var level:server.getAllLevels())level.noSave=true;
        try{server.saveEverything(true,false,false);}finally{for(var level:server.getAllLevels())level.noSave=false;}
        helper.assertTrue(ctx.nativeSaveStatus().requests()==requests,"save-off still requested an automatic VE checkpoint");
        var prior=ctx.checkpointStatus().committed();long events=levelSaves;
        try(var c=ctx.openRuntimeVerificationConnection()){
            SqliteTransactions.write(c,tx->{
                // A genuine unresolved writer locks the clock table. The main connection cannot capture it.
                try(var s=c.createStatement()){s.executeUpdate("UPDATE economic_time SET revision=revision");}
                helper.assertTrue(server.saveEverything(true,false,true),"VE capture failure blocked native normal save");
                try{ctx.awaitEconomicSave();helper.fail("Old saved future acknowledged the rejected current save");}catch(IllegalStateException expected){}
                try{server.saveEverything(true,true,true);helper.fail("flush reported success after rejected VE capture");}catch(IllegalStateException expected){}
                helper.assertTrue(ctx.checkpointStatus().committed().equals(prior),"Failed capture replaced trusted checkpoint");
                helper.assertTrue(levelSaves>=events+2,"Native level saves were skipped on VE failure");
                return null;
            });
        }
        helper.assertTrue(ctx.nativeSaveStatus().rejectedCaptures()==2,"Capture failure evidence was lost");
        server.saveEverything(true,true,true);
        helper.assertTrue(ctx.checkpointStatus().committed().economicTick()==ctx.getEconomicTime().acceptedTick(),"Fresh explicit save did not recover after lock release");
        Files.writeString(Path.of("save-failure-evidence.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("native",ctx.nativeSaveStatus(),"levelEvents",levelSaves,"checkpoint",ctx.checkpointStatus())));
        Files.writeString(Path.of("acceptance-result.txt"),"PASSED checkpointFailure");helper.succeed();
    }
}
