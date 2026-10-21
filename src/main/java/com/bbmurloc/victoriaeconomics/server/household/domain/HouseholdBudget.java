package com.bbmurloc.victoriaeconomics.server.household.domain;

import com.bbmurloc.victoriaeconomics.server.finance.domain.FinanceAccount;
import java.util.Objects;

/** Explicit purpose allowances, never balances. Unallocated cash stays unallocated. */
public record HouseholdBudget(RegionalHouseholdSector sector,long consumption,long savings,long carriedPeriod,long revision) {
    public HouseholdBudget {
        Objects.requireNonNull(sector);
        if(consumption<0||savings<0||carriedPeriod<0||revision<0)throw new IllegalArgumentException("Negative household allowance/version");
        Math.addExact(consumption,savings);
    }
    public long allocated(){return Math.addExact(consumption,savings);}
    public void requireCoveredBy(FinanceAccount account) {
        if(!sector.accountId().equals(account.id())||account.ownerKind()!=FinanceAccount.OwnerKind.REGION
                ||!sector.region().value().equals(account.ownerId())||!sector.currency().equals(account.currency())
                ||allocated()>account.available())throw new IllegalStateException("Budget exceeds real unreserved household cash or account ownership differs");
    }
    public HouseholdBudget allocate(long consume,long save){return new HouseholdBudget(sector,consume,save,carriedPeriod,Math.incrementExact(revision));}
    public HouseholdBudget moveToSavings(long amount){if(amount<=0)throw new IllegalArgumentException("Positive allowance movement required");return allocate(Math.subtractExact(consumption,amount),Math.addExact(savings,amount));}
    public HouseholdBudget moveToConsumption(long amount){if(amount<=0)throw new IllegalArgumentException("Positive allowance movement required");return allocate(Math.addExact(consumption,amount),Math.subtractExact(savings,amount));}
    public HouseholdBudget carryThrough(long period) {
        if(period<carriedPeriod)throw new IllegalArgumentException("Budget period cannot move backwards");
        return period==carriedPeriod?this:new HouseholdBudget(sector,consumption,savings,period,Math.incrementExact(revision));
    }
}
