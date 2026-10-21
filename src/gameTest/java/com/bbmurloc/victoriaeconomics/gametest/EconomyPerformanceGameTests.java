package com.bbmurloc.victoriaeconomics.gametest;

import com.bbmurloc.victoriaeconomics.server.ServerEconomyRuntime;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.finance.domain.TransferReceipt;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.RegionalLaborSupply;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteRegionalLaborSupplyRepository;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same formal Tick/payroll/payment/production paths in every scenario; isolated recorded test funding only. */
@GameTestHolder("victoria_economics_perftests")
@PrefixGameTestTemplate(false)
public final class EconomyPerformanceGameTests {
    @GameTest(template="empty",timeoutTicks=2000)
    public static void profileFormalEconomy(GameTestHelper helper)throws Exception{
        String scenario=System.getProperty("ve.perfScenario");
        if(!Set.of("empty","idle4","active4","idle16","active16","history4","active32").contains(scenario))throw new IllegalArgumentException("Unknown performance scenario");
        var server=helper.getLevel().getServer();var ctx=ServerEconomyRuntime.get(server);
        helper.assertTrue(ctx.getBuildingRegistry().getAll().isEmpty(),"Performance comparison requires a fresh isolated world");
        List<EconomicBuilding> buildings=new ArrayList<>();GameEconomyFixture fixture;
        if(!scenario.equals("empty")){
            fixture=new GameEconomyFixture(server);fixture.fund(10_000_000);
            try(var c=fixture.connect()){
                var supply=new SqliteRegionalLaborSupplyRepository(c,ctx.getOccupationRegistry(),new Object());
                SqliteTransactions.write(c,tx->{for(String role:List.of("laborer","machinist"))supply.inTransaction(tx).save(new RegionalLaborSupply(fixture.region,role,2000,1),0);return null;});
            }
            int count=scenario.endsWith("32")?32:scenario.endsWith("16")?16:4;
            for(int i=0;i<count;i++)buildings.add(fixture.create(10,4));
            if(scenario.equals("history4"))seedVerifiableHistory(fixture,buildings);
            if(scenario.startsWith("active")||scenario.equals("history4"))for(var b:buildings)ctx.getProductionService().startBatch(b.getId());
        }else fixture=null;
        long began=ctx.getEconomicTime().acceptedTick();var measuring=new AtomicBoolean();var finished=new AtomicBoolean();
        long saveInterval=Long.getLong("ve.perfSaveInterval",0),lastSaveTick[]=new long[]{began};
        helper.onEachTick(()->{
            if(finished.get())return;
            try{
                var current=ServerEconomyRuntime.get(server);long tick=current.getEconomicTime().acceptedTick();
                if(saveInterval>0&&tick-lastSaveTick[0]>=saveInterval){lastSaveTick[0]=tick;server.saveEverything(true,false,false);}
                if(tick-began>=150&&measuring.compareAndSet(false,true))PerformanceMeasurements.begin(1300);
                if(!PerformanceMeasurements.done())return;
                finished.set(true);
                for(var b:buildings){
                    var e=current.getProductionService().execution(b.getId());
                    helper.assertTrue(e.lastCompletedTick()==tick&&e.processingTick()==null,"Unconfirmed per-building Tick");
                    helper.assertTrue(current.getPayrollQuery().state(b.getId())==com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State.CURRENT,"Normal paid payroll path was bypassed or failed");
                    if(scenario.startsWith("active")||scenario.equals("history4")){
                        helper.assertTrue(e.lastBatch()!=null&&e.lastBatch().isCompleted(),"No real continuous completion");
                        helper.assertTrue(e.batch()!=null&&e.batch().getProgressUnits()==(tick-began)%200,"Repeated, omitted or idle production Tick");
                    }
                }
                var file=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("victoria_economics/economy.sqlite");
                current.flushEconomicSave();
                try(var c=SqliteEconomyDatabase.openReadOnly(file);var s=c.createStatement()){
                    EconomySchema.validateExisting(c);String settings;
                    try(var r=s.executeQuery("PRAGMA journal_mode")){r.next();settings="journal_mode="+r.getString(1);}
                    try(var r=s.executeQuery("PRAGMA synchronous")){r.next();settings+=",synchronous="+r.getInt(1);}
                    PerformanceMeasurements.write(scenario,tick,settings,Files.size(file),current);
                }
                helper.succeed();
            }catch(Exception failure){throw new IllegalStateException(failure);}
        });
    }
    /** Explicit test-only valid historical segments/ledger, not history pruning or a server mutation API. */
    private static void seedVerifiableHistory(GameEconomyFixture f,List<EconomicBuilding> buildings)throws Exception{
        try(var c=f.connect()){
            var wages=new SqlitePayrollRepository(c,new Object());
            SqliteTransactions.write(c,tx->{
                var repo=wages.inTransaction(tx);long tick=f.context().getEconomicTime().acceptedTick();
                for(var b:buildings)for(int i=0;i<10;i++){
                    repo.close(repo.currentForBuilding(b.getId()).id(),tick);repo.enroll(UUID.randomUUID(),b.getId(),f.currency);
                }return null;
            });
        }
        for(int i=0;i<250;i++){
            f.context().getFinanceService().transfer(new TransferReceipt(UUID.randomUUID(),f.companyCash,f.householdCash,f.currency,1));
            f.context().getFinanceService().transfer(new TransferReceipt(UUID.randomUUID(),f.householdCash,f.companyCash,f.currency,1));
        }
    }
}
