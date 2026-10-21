package com.bbmurloc.victoriaeconomics.gametest;

import com.bbmurloc.victoriaeconomics.server.diagnostics.EconomyDiagnostics;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import java.nio.file.*;
import java.util.*;

/** Full server method and economy measurements, buffered in memory and written once after sampling. */
public final class PerformanceMeasurements {
    private static EconomyDiagnostics.Recording recording;
    private static EconomyDiagnostics.Snapshot startup, fixtureAndWarmup, sample;
    private static final List<Long> ticks = new ArrayList<>(), nativeTicks = new ArrayList<>(), intervals = new ArrayList<>();
    private static final List<Long> savingTicks=new ArrayList<>(),normalTicks=new ArrayList<>(),payrollTicks=new ArrayList<>(),settlementTicks=new ArrayList<>();
    private static long savesBefore,payrollBefore,settlementsBefore;
    private static final List<String> counterKeys=List.of("sql.read","sql.dml","tx.physical","tx.savepoint","json.batch.decode","json.batch.encode","json.batch.reused","json.inventory.decode","json.inventory.encode","inventory.receipts.decoded","repository.execution.read","repository.methods.read");
    private static final Map<String,Map<String,Long>> tickWork=new TreeMap<>();
    private static long[] countersBefore;
    private static long started, previousStart;
    private static int requested;
    private static boolean pendingStart, sampling, done;
    private static final boolean details=Boolean.parseBoolean(System.getProperty("ve.perfDetails","true"));
    private static final EconomyDiagnostics.Snapshot empty=new EconomyDiagnostics.Snapshot(Map.of(),Map.of());
    static boolean enabled(){return Set.of("perf","checkpoint","crashRestart").contains(System.getProperty("ve.acceptanceMode","full"));}
    static void beforeContext(){if(enabled()&&details&&recording==null)recording=EconomyDiagnostics.start();}
    static void afterContext(){if(enabled()){startup=details?recording.snapshot():empty;if(details){if("crashRestart".equals(System.getProperty("ve.acceptanceMode")))recording.close();else recording.reset();}}}
    static EconomyDiagnostics.Snapshot startupForEvidence(){return startup;}
    static void begin(int samples){
        if(details&&recording==null)throw new IllegalStateException("Startup recording hook missing");
        fixtureAndWarmup=details?recording.snapshot():empty;requested=samples;previousStart=0;pendingStart=true;
    }
    public static void beforeTick(MinecraftServer server){
        if(pendingStart){if(details)recording.reset();pendingStart=false;sampling=true;}
        if(!sampling)return;
        started=System.nanoTime();
        if(previousStart!=0)intervals.add(started-previousStart);
        previousStart=started;
        savesBefore=com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime.get(server).nativeSaveStatus().requests();
        if(details){payrollBefore=recording.eventCount("payroll.cycle");settlementsBefore=recording.eventCount("inventory.settlement");countersBefore=counterKeys.stream().mapToLong(recording::eventCount).toArray();}
    }
    public static void afterTick(MinecraftServer server){
        if(!sampling||started==0)return;
        long duration=System.nanoTime()-started;ticks.add(duration);
        long savesAfter=com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime.get(server).nativeSaveStatus().requests();
        (savesAfter>savesBefore?savingTicks:normalTicks).add(duration);
        if(details&&recording.eventCount("payroll.cycle")>payrollBefore)payrollTicks.add(duration);
        if(details&&recording.eventCount("inventory.settlement")>settlementsBefore)settlementTicks.add(duration);
        if(details){
            var classes=new ArrayList<String>();
            if(savesAfter>savesBefore)classes.add("save");
            if(recording.eventCount("payroll.cycle")>payrollBefore)classes.add("payroll");
            if(recording.eventCount("inventory.settlement")>settlementsBefore)classes.add("settlement");
            if(classes.isEmpty())classes.add("ordinary");
            for(String category:classes){var work=tickWork.computeIfAbsent(category,k->new TreeMap<>());work.merge("ticks",1L,Long::sum);for(int i=0;i<counterKeys.size();i++){String key=counterKeys.get(i);work.merge(key,recording.eventCount(key)-countersBefore[i],Long::sum);}}
        }
        nativeTicks.add(server.getTickTimesNanos()[server.getTickCount()%100]);
        if(ticks.size()==requested){sampling=false;sample=details?recording.snapshot():empty;if(details)recording.close();done=true;}
    }
    static boolean done(){return done;}
    static void write(String scenario,long acceptedTick,String settings,long fileBytes,com.bbmurloc.victoriaeconomics.server.ServerEconomyContext context)throws Exception{
        var result=new TreeMap<String,Object>();
        result.put("label",System.getProperty("ve.perfLabel"));result.put("scenario",scenario);
        result.put("details",details);
        result.put("normalTicks",EconomyDiagnostics.summarize(normalTicks));result.put("nativeSavingTicks",EconomyDiagnostics.summarize(savingTicks));
        if(details){result.put("payrollBoundaryTicks",EconomyDiagnostics.summarize(payrollTicks));result.put("batchSettlementTicks",EconomyDiagnostics.summarize(settlementTicks));}
        result.put("serverTick",EconomyDiagnostics.summarize(ticks));result.put("minecraftBeforePost",EconomyDiagnostics.summarize(nativeTicks));
        result.put("startInterval",EconomyDiagnostics.summarize(intervals));
        result.put("observedTps",1e9/intervals.stream().mapToDouble(Long::doubleValue).average().orElseThrow());
        result.put("startup",startup);result.put("fixtureAndWarmup",fixtureAndWarmup);result.put("sample",sample);
        if(details)result.put("classifiedTickWork",tickWork);
        result.put("acceptedTick",acceptedTick);result.put("sqlite",settings);result.put("databaseBytes",fileBytes);
        result.put("runtimeStorage","shared-memory SQLite; disk is complete checkpoint only");
        result.put("checkpoint",context.checkpointStatus());result.put("nativeSave",context.nativeSaveStatus());
        result.put("checkpointTimings",context.checkpointTimings());
        result.put("testSaveInterval",Long.getLong("ve.perfSaveInterval",0));
        result.put("java",System.getProperty("java.version"));result.put("vm",System.getProperty("java.vm.name"));
        result.put("maxHeapBytes",Runtime.getRuntime().maxMemory());result.put("processors",Runtime.getRuntime().availableProcessors());
        result.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        Files.writeString(Path.of("performance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+System.lineSeparator());
        Files.writeString(Path.of("acceptance-result.txt"),"PASSED "+System.getProperty("ve.acceptanceMode"));
        System.out.println("VE performance "+scenario+": "+result.get("serverTick")+", TPS="+result.get("observedTps"));
    }
}
