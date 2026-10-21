package com.bbmurloc.victoriaeconomics.gametest;

import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Second JVM launch against the preceding isolated world, not an in-process Context restart. */
@GameTestHolder("victoria_economics_restarttests")
@PrefixGameTestTemplate(false)
public final class RestartAcceptanceGameTests {
    private static long baselineTick,logicalTicks;
    private static List<String> baseline;
    private static boolean verified;
    private static boolean enabled(){return "restart".equals(System.getProperty("ve.acceptanceMode"));}
    static void beforeContext(MinecraftServer server){
        if(!enabled())return;
        var file=server.getWorldPath(LevelResource.ROOT).resolve("victoria_economics/economy.sqlite");
        if(!file.toAbsolutePath().normalize().toString().contains("task-e-gametest")||!Files.isRegularFile(file))throw new IllegalStateException("Restart check requires an existing isolated acceptance world");
        try(var c=SqliteEconomyDatabase.openReadOnly(file)){
            baseline=businessSnapshot(c);try(var s=c.createStatement();var r=s.executeQuery("SELECT accepted_tick FROM economic_time")){r.next();baselineTick=r.getLong(1);}
            if(baselineTick<2400)throw new IllegalStateException("No prior complete two-period acceptance run");
        }catch(SQLException failure){throw new IllegalStateException(failure);}
    }
    static void afterContext(MinecraftServer server){
        if(!enabled())return;
        if(baseline==null)throw new IllegalStateException("Startup baseline hook did not run before the formal Context");
        var context=ServerEconomyRuntime.get(server);
        if(context.getEconomicTime().acceptedTick()!=baselineTick)throw new IllegalStateException("Process restart invented offline economic time");
        try(var c=context.openRuntimeVerificationConnection()){
            if(!businessSnapshot(c).equals(baseline))throw new IllegalStateException("Clean process restart replayed production or wages/finance before a logical Tick");
            verified=true;System.out.println("VE process restart verified at accepted Tick "+baselineTick+" with identical production, liabilities and cash");
        }catch(SQLException failure){throw new IllegalStateException(failure);}
    }
    static void afterLogicalTick(){if(enabled())logicalTicks=Math.incrementExact(logicalTicks);}
    @GameTest(template="empty",timeoutTicks=100)
    public static void persistedBusinessSurvivesWholeServerProcessRestart(GameTestHelper helper){
        helper.assertTrue(verified,"Actual process restart was not verified");
        helper.assertTrue(ServerEconomyRuntime.get(helper.getLevel().getServer()).getEconomicTime().acceptedTick()==Math.addExact(baselineTick,logicalTicks),"Persistent time differs from actual post-restart logical callbacks");
        try{Files.writeString(Path.of("acceptance-result.txt"),"PASSED restart");}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        helper.succeed();
    }
    private static List<String> businessSnapshot(Connection c){return SqliteTransactions.read(c,tx->{
        var result=new ArrayList<String>();
        for(String query:List.of("SELECT accepted_tick FROM economic_time","SELECT batch_id,progress_units,status FROM production_batches ORDER BY batch_id","SELECT id,balance FROM finance_accounts ORDER BY id","SELECT id,last_accrued_tick,accrued,matured,paid,remainder FROM payroll_accruals ORDER BY id","SELECT segment_id,period_id,amount,paid FROM payroll_liabilities ORDER BY segment_id,period_id","SELECT id,status FROM building_lifecycle_operations ORDER BY id","SELECT location_id,state_json FROM inventory_locations ORDER BY location_id")){
            try(var s=c.createStatement();var r=s.executeQuery(query)){
                int columns=r.getMetaData().getColumnCount();while(r.next()){var row=new StringBuilder(query);for(int i=1;i<=columns;i++)row.append('|').append(r.getString(i));result.add(row.toString());}
            }
        }
        return List.copyOf(result);
    });}
}
