package com.bbmurloc.victoriaeconomics.server.household.port;

import com.bbmurloc.victoriaeconomics.server.household.domain.HouseholdBudget;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;

public interface HouseholdBudgetRepository {
    HouseholdBudget load(RegionId region,CurrencyId currency);
}
