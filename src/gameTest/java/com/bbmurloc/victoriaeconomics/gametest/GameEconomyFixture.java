package com.bbmurloc.victoriaeconomics.gametest;

import com.bbmurloc.victoriaeconomics.server.*;
import com.bbmurloc.victoriaeconomics.server.building.EconomicBuilding;
import com.bbmurloc.victoriaeconomics.server.company.domain.*;
import com.bbmurloc.victoriaeconomics.server.company.infrastructure.SqliteCompanyRepository;
import com.bbmurloc.victoriaeconomics.server.region.domain.*;
import com.bbmurloc.victoriaeconomics.server.region.infrastructure.SqliteRegionRepository;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.RegionalWageRate;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.RegionalLaborSupply;
import com.bbmurloc.victoriaeconomics.server.workforce.infrastructure.SqliteRegionalLaborSupplyRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Explicit, persisted, isolated test identities/funding. No production API or distributable test money. */
final class GameEconomyFixture {
    final MinecraftServer server;
    final Path database;
    final CompanyId company=new CompanyId(UUID.randomUUID()),buyer=new CompanyId(UUID.randomUUID());
    final RegionId region=new RegionId(UUID.randomUUID());
    final CurrencyId currency=new CurrencyId(UUID.randomUUID());
    final UUID companyCash=UUID.randomUUID(),householdCash=UUID.randomUUID();
    GameEconomyFixture(MinecraftServer server)throws Exception{
        this.server=server;database=server.getWorldPath(LevelResource.ROOT).resolve("victoria_economics/economy.sqlite");
        if(!Boolean.getBoolean("neoforge.gameTestServer")||!database.toAbsolutePath().normalize().toString().contains("task-e-gametest"))
            throw new IllegalStateException("Acceptance fixture refuses non-isolated worlds");
        try(var c=connect()) {
            var companies=new SqliteCompanyRepository(c,new Object());companies.create(new Company(company));companies.create(new Company(buyer));
            new SqliteRegionRepository(c,new Object()).create(new Region(region));
            var ctx=context();ctx.getFinanceService().registerCurrency(currency,"Isolated NeoForge acceptance currency");
            ctx.getPayrollAccrualService().configureRate(new RegionalWageRate(region,currency,ctx.getEconomicTime().acceptedTick(),1200,0));
            ctx.getFinanceService().open(companyCash,FinanceAccount.OwnerKind.COMPANY,company.value(),currency);
            ctx.getHouseholds().open(region,currency,householdCash);
            var supply=new SqliteRegionalLaborSupplyRepository(c,ctx.getOccupationRegistry(),new Object());
            SqliteTransactions.write(c,tx->{for(String role:List.of("laborer","machinist"))supply.inTransaction(tx).create(new RegionalLaborSupply(region,role,100,0));return null;});
        }
    }
    ServerEconomyContext context(){return ServerEconomyRuntime.get(server);}
    Connection connect(){return SqliteEconomyDatabase.open(database);}
    EconomicBuilding create(int laborers,int machinists)throws Exception{
        var before=context().getBuildingRegistry().getAll().stream().map(EconomicBuilding::getId).collect(java.util.stream.Collectors.toSet());
        if(command("ve debug create tooling_workshop 1000000 "+company+" "+region,4)!=1)throw new AssertionError("Actual creation command failed");
        var b=context().getBuildingRegistry().getAll().stream().filter(x->!before.contains(x.getId())).findFirst().orElseThrow();
        context().getInventoryStore().deposit(b.getId(),Map.of("wood",100000L,"iron",100000L));
        context().getStaffingService().requestTarget(b.getId(),"laborer",laborers);context().getStaffingService().requestTarget(b.getId(),"machinist",machinists);
        return b;
    }
    int command(String command,int permission)throws Exception{return server.getCommands().getDispatcher().execute(command,server.createCommandSourceStack().withPermission(permission).withSuppressedOutput());}
    long scalar(String sql)throws Exception{try(var c=connect();var s=c.createStatement();var r=s.executeQuery(sql)){if(!r.next())throw new AssertionError("Missing SQL fact");return r.getLong(1);}}
    /** Real test-only external credit with immutable provenance and a full ledger, not a mock balance. */
    void fund(long amount)throws Exception{
        if(amount<=0)throw new IllegalArgumentException("Positive test grant required");
        try(var c=connect()) {SqliteTransactions.write(c,tx->{
            var account=new SqliteFinanceAccountRepository(c,new Object()).inTransaction(tx).load(companyCash);
            long next=Math.addExact(account.balance(),amount);String event="isolated-gametest-grant:"+UUID.randomUUID();
            try(var s=c.prepareStatement("INSERT INTO finance_events VALUES(?,'EXTERNAL_CREDIT',NULL,?,?,?,'Isolated NeoForge acceptance grant; src/gameTest only; never a server issuance command')")) {
                s.setString(1,event);s.setString(2,companyCash.toString());s.setString(3,currency.toString());s.setLong(4,amount);s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO finance_ledger(event_id,line,account_id,delta,balance_after) VALUES(?,0,?,?,?)")) {
                s.setString(1,event);s.setString(2,companyCash.toString());s.setLong(3,amount);s.setLong(4,next);s.executeUpdate();
            }
            try(var s=c.prepareStatement("UPDATE finance_accounts SET balance=?,revision=? WHERE id=? AND revision=?")) {
                s.setLong(1,next);s.setLong(2,Math.incrementExact(account.revision()));s.setString(3,companyCash.toString());s.setLong(4,account.revision());if(s.executeUpdate()!=1)throw new IllegalStateException("Test grant CAS failed");
            }
            return null;
        });}
    }
}
