package com.bbmurloc.victoriaeconomics.server.household;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld;
import com.bbmurloc.victoriaeconomics.server.household.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HouseholdBudgetTest {
    RegionalHouseholdSector sector=new RegionalHouseholdSector(TestIdentities.REGION,FinancialTestWorld.CURRENCY,UUID.randomUUID());
    @Test void allowancesAreExplicitAndMoveWithoutChangingTheirSum(){var b=new HouseholdBudget(sector,30,70,1,0);var moved=b.moveToConsumption(10);assertEquals(40,moved.consumption());assertEquals(60,moved.savings());assertEquals(100,moved.allocated());assertEquals(1,moved.revision());}
    @Test void carryDoesNotAllocateAdditionalCash(){var b=new HouseholdBudget(sector,30,70,1,0);var carried=b.carryThrough(3);assertEquals(30,carried.consumption());assertEquals(70,carried.savings());assertSame(carried,carried.carryThrough(3));assertThrows(IllegalArgumentException.class,()->carried.carryThrough(2));}
    @Test void negativeAndOverflowAllowancesAreRejected(){assertThrows(IllegalArgumentException.class,()->new HouseholdBudget(sector,-1,0,0,0));assertThrows(ArithmeticException.class,()->new HouseholdBudget(sector,Long.MAX_VALUE,1,0,0));var b=new HouseholdBudget(sector,1,0,0,0);assertThrows(IllegalArgumentException.class,()->b.moveToSavings(2));assertThrows(IllegalArgumentException.class,()->b.moveToSavings(0));}
    @Test void actualReservationAndOwnershipConstrainTheWholeBudget(){var account=new FinanceAccount(sector.accountId(),FinanceAccount.OwnerKind.REGION,sector.region().value(),sector.currency(),100,20,0);new HouseholdBudget(sector,40,40,0,0).requireCoveredBy(account);assertThrows(IllegalStateException.class,()->new HouseholdBudget(sector,40,41,0,0).requireCoveredBy(account));assertThrows(IllegalStateException.class,()->new HouseholdBudget(sector,0,0,0,0).requireCoveredBy(new FinanceAccount(UUID.randomUUID(),FinanceAccount.OwnerKind.REGION,sector.region().value(),sector.currency(),100,0,0)));}
}
