package com.bbmurloc.victoriaeconomics.server.financial;

import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld.CURRENCY;

class PayrollAccrualDomainTest {
    PayrollAccrual start(long n) { return PayrollAccrual.start(UUID.randomUUID(),UUID.randomUUID(),TestIdentities.COMPANY,TestIdentities.REGION,CURRENCY,0,n); }
    RegionalWageRate rate(long tick,long w,long rev) { return new RegionalWageRate(TestIdentities.REGION,CURRENCY,tick,w,rev); }
    @Test void everyTickRetainsRemainderUntilExactly1200AndMaturesOnce() {
        var p=start(1); var history=List.of(rate(0,1,0));
        for(int i=1;i<=1200;i++) {
            var result=p.accrueThrough(i,history);p=result.accrual();
            assertEquals(i/1200,p.accrued());assertEquals(i%1200,p.remainder());
            assertEquals(i==1200?1:0,result.liabilities().size());
        }
        assertEquals(1,p.matured());assertEquals(0,p.paid());assertEquals(0,p.unmatured());
        assertSame(p,p.accrueThrough(1200,history).accrual());
    }
    @Test void arbitrarySegmentationEqualsContinuousIncludingRateChangesAndBoundaries() {
        var history=List.of(rate(0,7,0),rate(317,13,1),rate(1444,23,2));var initial=start(9);
        var once=initial.accrueThrough(3921,history);var segmented=initial;var liabilities=new ArrayList<PayrollLiability>();
        var random=new Random(42);long t=0;
        while(t<3921) {t=Math.min(3921,t+1+random.nextInt(83));var r=segmented.accrueThrough(t,history);segmented=r.accrual();liabilities.addAll(r.liabilities());}
        assertEquals(once.accrual().accrued(),segmented.accrued());assertEquals(once.accrual().remainder(),segmented.remainder());
        assertEquals(once.accrual().unmatured(),segmented.unmatured());assertEquals(once.liabilities(),liabilities);
    }
    @Test void increasedAndDecreasedEmployeesAccrueOldCountsFirst() {
        var history=List.of(rate(0,1200,0));var p=start(1);
        assertThrows(IllegalArgumentException.class,()->p.withEmployeesAtCursor(2,100));
        var a=p.accrueThrough(100,history).accrual().withEmployeesAtCursor(3,100);
        var b=a.accrueThrough(200,history).accrual().withEmployeesAtCursor(0,200);
        var end=b.accrueThrough(1200,history).accrual();assertEquals(400,end.accrued());assertEquals(400,end.unpaid());
    }
    @Test void wageChangeAffectsOnlyFutureHalfOpenInterval() {
        var p=start(2).accrueThrough(1200,List.of(rate(0,1,0),rate(600,3,1))).accrual();
        assertEquals(4,p.matured());assertEquals(0,p.remainder());
    }
    @Test void closingPreservesFractionForHistoricalCompanyAndDoesNotTransferIt() {
        var history=List.of(rate(0,1,0));var old=start(1).accrueThrough(601,history).accrual().closeAtCursor(601);
        var next=PayrollAccrual.start(UUID.randomUUID(),old.buildingId(),new CompanyId(UUID.randomUUID()),old.region(),CURRENCY,601,1);
        old=old.accrueThrough(2400,history).accrual();next=next.accrueThrough(2400,history).accrual();
        assertEquals(601,old.remainder());assertEquals(0,old.accrued());assertEquals(1,next.accrued());assertEquals(599,next.remainder());
        assertNotEquals(old.company(),next.company());
    }
    @Test void missingRateIsNotZeroButExplicitZeroIsValid() {
        var p=start(1);assertThrows(IllegalStateException.class,()->p.accrueThrough(1,List.of()));
        assertEquals(0,p.accrueThrough(1200,List.of(rate(0,0,0))).accrual().accrued());
    }
    @Test void exactNumeratorCanExceedLongButFinalAmountCannotOverflow() {
        var p=start(2);var history=List.of(rate(0,Long.MAX_VALUE,0));
        assertEquals(Long.MAX_VALUE/600,p.accrueThrough(1,history).accrual().accrued());
        assertThrows(ArithmeticException.class,()->p.accrueThrough(1200,history));
    }
    @Test void negativeInputsAndRegressingCursorAreRejected() {
        assertThrows(IllegalArgumentException.class,()->start(-1));assertThrows(IllegalArgumentException.class,()->rate(0,-1,0));
        var p=start(1).accrueThrough(5,List.of(rate(0,1,0))).accrual();
        assertThrows(IllegalArgumentException.class,()->p.accrueThrough(4,List.of(rate(0,1,0))));
        assertThrows(IllegalArgumentException.class,()->p.withEmployeesAtCursor(-1,5));
    }
}
