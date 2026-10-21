package com.bbmurloc.victoriaeconomics.server.household.application;

import com.bbmurloc.victoriaeconomics.server.household.domain.HouseholdBudget;
import com.bbmurloc.victoriaeconomics.server.household.infrastructure.SqliteHouseholdBudgetRepository;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.sql.Connection;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Explicit registration and allowances only. No default ratio, issuance or consumption sink. */
public final class RegionalHouseholdService {
    private final Connection connection;private final Object lock;private final SqliteHouseholdBudgetRepository repository;
    public RegionalHouseholdService(Connection c,Object lock){connection=c;this.lock=lock;repository=new SqliteHouseholdBudgetRepository(c,lock);}
    public HouseholdBudget open(RegionId region,CurrencyId currency,UUID account){return write(tx->repository.inTransaction(tx).open(region,currency,account));}
    public HouseholdBudget budget(RegionId region,CurrencyId currency){return repository.load(region,currency);}
    public HouseholdBudget allocate(RegionId region,CurrencyId currency,long consumption,long savings,long expected){return change(region,currency,expected,b->b.allocate(consumption,savings));}
    public HouseholdBudget moveToSavings(RegionId region,CurrencyId currency,long amount,long expected){return change(region,currency,expected,b->b.moveToSavings(amount));}
    public HouseholdBudget moveToConsumption(RegionId region,CurrencyId currency,long amount,long expected){return change(region,currency,expected,b->b.moveToConsumption(amount));}
    public HouseholdBudget carryThrough(RegionId region,CurrencyId currency,long period,long expected){return change(region,currency,expected,b->b.carryThrough(period));}
    private HouseholdBudget change(RegionId region,CurrencyId currency,long expected,UnaryOperator<HouseholdBudget> action){return write(tx->{
        var repo=repository.inTransaction(tx);var before=repo.load(region,currency);
        if(before.revision()!=expected)throw new IllegalStateException("Household budget CAS conflict");
        var after=action.apply(before);return after==before?before:repo.save(after,expected);
    });}
    private <T>T write(SqliteTransactions.WriteWork<T> work){synchronized(lock){
        try{return SqliteTransactions.write(connection,work);}catch(CommitOutcomeUnknownException unknown){SqliteConnectionGuard.quarantine(connection,unknown);throw unknown;}
    }}
}
