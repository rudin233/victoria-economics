package com.bbmurloc.victoriaeconomics.server.payroll;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.financial.*;
import com.bbmurloc.victoriaeconomics.server.payroll.application.*;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.household.application.RegionalHouseholdService;
import com.bbmurloc.victoriaeconomics.server.company.domain.*;
import com.bbmurloc.victoriaeconomics.server.region.domain.*;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.RegionalLaborSupply;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Real, isolated funding records and real formal headcount changes. Not a server data source. */
public final class PaymentTestWorld implements AutoCloseable {
    public final FinancialTestWorld f;public final Connection c;
    public final CompanyPayrollService payments;public final PayrollPeriodService periods;public final RegionalHouseholdService households;
    public PaymentTestWorld(Path path)throws Exception{this(path,UnaryOperator.identity());}
    public PaymentTestWorld(Path path,UnaryOperator<Connection> decorate)throws Exception{
        f=new FinancialTestWorld(path,decorate);c=f.c;payments=new CompanyPayrollService(c,f.world.lock);periods=new PayrollPeriodService(c,f.world.lock);households=new RegionalHouseholdService(c,f.world.lock);
    }
    public UUID companyAccount(){return companyAccount(TestIdentities.COMPANY,FinancialTestWorld.CURRENCY);}
    public UUID companyAccount(CompanyId company,CurrencyId currency){return SqliteTransactions.write(c,tx->{
        var accounts=f.accounts.inTransaction(tx);var old=accounts.find(FinanceAccount.OwnerKind.COMPANY,company.value(),currency);
        return old.isPresent()?old.get().id():accounts.open(UUID.randomUUID(),FinanceAccount.OwnerKind.COMPANY,company.value(),currency).id();
    });}
    public UUID recipient(){return recipient(TestIdentities.REGION,FinancialTestWorld.CURRENCY);}
    public UUID recipient(RegionId region,CurrencyId currency){return SqliteTransactions.write(c,tx->{
        var accounts=f.accounts.inTransaction(tx);UUID id=accounts.find(FinanceAccount.OwnerKind.REGION,region.value(),currency).map(FinanceAccount::id).orElseGet(UUID::randomUUID);
        return new com.bbmurloc.victoriaeconomics.server.household.infrastructure.SqliteHouseholdBudgetRepository(c,f.world.lock).inTransaction(tx).open(region,currency,id).sector().accountId();
    });}
    public UUID building(int employees,long wage)throws Exception{return building(UUID.randomUUID(),TestIdentities.COMPANY,TestIdentities.REGION,FinancialTestWorld.CURRENCY,employees,wage);}
    public UUID building(UUID id,CompanyId company,RegionId region,CurrencyId currency,int employees,long wage)throws Exception{
        if(f.world.companies.find(company).isEmpty())f.world.companies.create(new Company(company));
        if(f.world.regions.find(region).isEmpty())f.world.regions.create(new Region(region));
        try(var s=c.prepareStatement("SELECT 1 FROM currencies WHERE id=?")){s.setString(1,currency.toString());try(var r=s.executeQuery()){if(!r.next())f.finance.registerCurrency(currency,"Explicit payment test currency");}}
        long rates;try(var s=c.prepareStatement("SELECT COUNT(*) FROM regional_wage_rates WHERE region_id=?")){s.setString(1,region.toString());try(var r=s.executeQuery()){r.next();rates=r.getLong(1);}}
        if(rates==0)f.accrual.configureRate(new RegionalWageRate(region,currency,f.time.load().acceptedTick(),wage,0));
        else if(f.scalar("SELECT COUNT(*) FROM payroll_accruals")==0)f.sql("UPDATE regional_wage_rates SET amount="+wage); // isolated pre-enrollment fixture only
        SqliteTransactions.write(c,tx->{var supply=f.world.supply.inTransaction(tx);if(supply.find(region,"laborer").isEmpty())supply.create(new RegionalLaborSupply(region,"laborer",1000,0));return null;});
        f.world.buildings.createBuilding(id,"tooling_workshop",company,region);f.world.commands.requestTarget(id,"laborer",employees);return id;
    }
    public void mature(long period){f.acceptedTimeFixture(Math.multiplyExact(period,1200L));f.accrual.recoverAcceptedTime();}
    public PayrollPaymentRun.Command command(long period){return PayrollPaymentRun.Command.forPeriod(TestIdentities.COMPANY,period);}
    public PayrollPaymentRun pay(long period){payments.preparePeriod(period);return payments.pay(command(period));}
    public long scalar(String sql)throws SQLException{return f.scalar(sql);}
    public void sql(String sql)throws SQLException{f.sql(sql);}
    public void fund(UUID account,long amount){f.fundingFixture(account,amount);}
    @Override public void close()throws Exception{f.close();}
}
