package com.bbmurloc.victoriaeconomics.server.household.domain;

import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import java.util.*;

/** Identity and account ownership only; FinanceAccount owns every unit of actual cash. */
public record RegionalHouseholdSector(RegionId region,CurrencyId currency,UUID accountId) {
    public RegionalHouseholdSector { Objects.requireNonNull(region);Objects.requireNonNull(currency);Objects.requireNonNull(accountId); }
}
