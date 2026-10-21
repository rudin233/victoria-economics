package com.bbmurloc.victoriaeconomics.server.household;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.household.domain.HouseholdBudget;
import com.bbmurloc.victoriaeconomics.server.payroll.PaymentTestWorld;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HouseholdBudgetPersistenceTest {
    @TempDir Path dir;Path file(){return dir.resolve("household.sqlite");}
    HouseholdBudget budget(PaymentTestWorld w){return w.households.budget(TestIdentities.REGION,FinancialTestWorld.CURRENCY);}
    HouseholdBudget allocate(PaymentTestWorld w,long c,long s,long expected){return w.households.allocate(TestIdentities.REGION,FinancialTestWorld.CURRENCY,c,s,expected);}
    UUID cash(PaymentTestWorld w,long amount){UUID company=w.companyAccount(),region=w.recipient();w.fund(company,amount);w.f.finance.transfer(new TransferReceipt(UUID.randomUUID(),company,region,FinancialTestWorld.CURRENCY,amount));return region;}
    @Test void noImplicitRatioAndAccrualAloneDoesNotCreateHouseholdCash()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(2,5);UUID source=w.companyAccount(),region=w.recipient();w.fund(source,10);w.mature(1);
            assertEquals(0,w.f.accounts.load(region).balance());assertEquals(0,budget(w).allocated());assertThrows(IllegalStateException.class,()->allocate(w,1,0,0));
            w.pay(1);assertEquals(10,w.f.accounts.load(region).balance());assertEquals(0,budget(w).allocated());allocate(w,3,7,0);assertEquals(10,budget(w).allocated());
        }
    }
    @Test void allowanceMovesAndCrossPeriodCarryHaveNoCashOrLedgerEffects()throws Exception{
        UUID region;
        try(var w=new PaymentTestWorld(file())){
            region=cash(w,100);var before=allocate(w,30,60,0);long ledger=w.scalar("SELECT COUNT(*) FROM finance_ledger");
            var moved=w.households.moveToConsumption(TestIdentities.REGION,FinancialTestWorld.CURRENCY,10,before.revision());assertEquals(40,moved.consumption());assertEquals(50,moved.savings());
            w.mature(2);w.periods.recoverAcceptedTime();assertEquals(2,budget(w).carriedPeriod());assertEquals(40,budget(w).consumption());assertEquals(50,budget(w).savings());
            assertEquals(100,w.f.accounts.load(region).balance());assertEquals(ledger,w.scalar("SELECT COUNT(*) FROM finance_ledger"));
            var carried=budget(w);assertEquals(carried,w.households.carryThrough(TestIdentities.REGION,FinancialTestWorld.CURRENCY,2,carried.revision()));
            assertThrows(IllegalStateException.class,()->w.households.carryThrough(TestIdentities.REGION,FinancialTestWorld.CURRENCY,3,carried.revision()));
        }
        try(var w=new PaymentTestWorld(file())){assertEquals(40,budget(w).consumption());assertEquals(100,w.f.accounts.load(region).balance());}
    }
    @Test void existingReservationsAndNewLocksCannotDoubleAllocateTheSameCash()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            UUID region=cash(w,100);var old=new CashReservation(UUID.randomUUID(),region,20,"existing isolated order",true);w.f.finance.reserve(old);
            assertThrows(IllegalStateException.class,()->allocate(w,50,31,0));allocate(w,50,30,0);
            assertThrows(IllegalStateException.class,()->w.f.finance.reserve(new CashReservation(UUID.randomUUID(),region,1,"cannot seize budget",true)));
            assertThrows(IllegalStateException.class,()->w.f.finance.transfer(new TransferReceipt(UUID.randomUUID(),region,w.companyAccount(),FinancialTestWorld.CURRENCY,1)));
            allocate(w,40,30,budget(w).revision());var next=new CashReservation(UUID.randomUUID(),region,10,"explicitly freed allowance",true);w.f.finance.reserve(next);
            assertEquals(70,budget(w).allocated());assertEquals(70,w.f.accounts.load(region).available());w.f.finance.release(next.id(),region);assertEquals(70,budget(w).allocated());assertEquals(80,w.f.accounts.load(region).available());
        }
    }
    @Test void staleRevisionOverAllocationAndOverflowNeverChangeCash()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            UUID region=cash(w,100);allocate(w,40,40,0);
            assertThrows(IllegalStateException.class,()->allocate(w,50,0,0));assertThrows(IllegalStateException.class,()->allocate(w,60,41,1));assertThrows(ArithmeticException.class,()->allocate(w,Long.MAX_VALUE,1,1));
            assertEquals(80,budget(w).allocated());assertEquals(100,w.f.accounts.load(region).balance());
        }
    }
    @Test void failedBudgetWriteRollsBackAndDoesNotTouchLedger()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            cash(w,100);long ledger=w.scalar("SELECT COUNT(*) FROM finance_ledger");w.sql("CREATE TRIGGER fail_budget BEFORE UPDATE ON regional_household_budgets BEGIN SELECT RAISE(ABORT,'budget failure'); END");
            assertThrows(IllegalStateException.class,()->allocate(w,60,20,0));assertEquals(0,budget(w).allocated());assertEquals(ledger,w.scalar("SELECT COUNT(*) FROM finance_ledger"));
            w.sql("DROP TRIGGER fail_budget");
        }
    }
    @Test void failedSectorInitializationRollsBackAccountLedgerAndSectorTogether()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            UUID account=UUID.randomUUID();w.sql("CREATE TRIGGER fail_budget BEFORE INSERT ON regional_household_budgets BEGIN SELECT RAISE(ABORT,'budget failure'); END");
            assertThrows(IllegalStateException.class,()->w.households.open(TestIdentities.REGION,FinancialTestWorld.CURRENCY,account));
            assertEquals(0,w.scalar("SELECT COUNT(*) FROM finance_accounts"));assertEquals(0,w.scalar("SELECT COUNT(*) FROM regional_household_sectors"));assertEquals(0,w.scalar("SELECT COUNT(*) FROM finance_ledger"));
        }
    }
}
