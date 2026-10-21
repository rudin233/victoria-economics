package com.bbmurloc.victoriaeconomics.server.payroll;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentDomainTest {
    UUID low=UUID.fromString("00000000-0000-0000-0000-000000000001"),high=UUID.fromString("80000000-0000-0000-0000-000000000001");
    @Test void exactProportionsConserveTheWholeBudget(){assertEquals(Map.of(low,2L,high,3L),ProportionalPaymentAllocator.allocate(Map.of(low,4L,high,6L),5));}
    @Test void canonicalBuildingOrderBreaksRemainderTies(){assertEquals(Map.of(low,1L,high,0L),ProportionalPaymentAllocator.allocate(Map.of(high,1L,low,1L),1));}
    @Test void fullAndZeroBudgetsNeverOverpay(){assertEquals(Map.of(low,7L),ProportionalPaymentAllocator.allocate(Map.of(low,7L),100));assertEquals(Map.of(low,0L),ProportionalPaymentAllocator.allocate(Map.of(low,7L),0));}
    @Test void exactNumeratorCanExceedLongWhileFinalAmountsRemainRepresentable(){var result=ProportionalPaymentAllocator.allocate(Map.of(low,Long.MAX_VALUE-1,high,1L),Long.MAX_VALUE-2);assertEquals(Long.MAX_VALUE-2,Math.addExact(result.get(low),result.get(high)));}
    @Test void invalidDebtsAndTotalOverflowAreRejected(){assertThrows(ArithmeticException.class,()->ProportionalPaymentAllocator.allocate(Map.of(low,Long.MAX_VALUE,high,1L),1));assertThrows(IllegalArgumentException.class,()->ProportionalPaymentAllocator.allocate(Map.of(low,0L),1));assertThrows(IllegalArgumentException.class,()->ProportionalPaymentAllocator.allocate(Map.of(low,-1L),1));assertThrows(IllegalArgumentException.class,()->ProportionalPaymentAllocator.allocate(Map.of(low,1L),-1));}
    PayrollPaymentLine line(long debt){return new PayrollPaymentLine(UUID.randomUUID(),1,low,TestIdentities.REGION,FinancialTestWorld.CURRENCY,UUID.randomUUID(),debt,0);}
    @Test void partialMultiSourceGroupHasNoInventedSplitPolicy(){assertThrows(IllegalStateException.class,()->PayrollPaymentPlanner.allocate(List.of(line(3),line(5)),4));}
    @Test void fullOrZeroMultiSourceGroupsAreUnambiguous(){var sources=List.of(line(3),line(5));assertEquals(List.of(3L,5L),PayrollPaymentPlanner.allocate(sources,8).stream().map(PayrollPaymentLine::amount).toList());assertEquals(List.of(0L,0L),PayrollPaymentPlanner.allocate(sources,0).stream().map(PayrollPaymentLine::amount).toList());}
    @Test void identityIsOneCompanyPeriodAcrossCurrencies(){var a=PayrollPaymentRun.Command.forPeriod(TestIdentities.COMPANY,1);assertEquals(a,PayrollPaymentRun.Command.forPeriod(TestIdentities.COMPANY,1));assertThrows(IllegalArgumentException.class,()->new PayrollPaymentRun.Command(a.id(),TestIdentities.COMPANY,2));assertThrows(ArithmeticException.class,()->PayrollPaymentRun.Command.forPeriod(TestIdentities.COMPANY,Long.MAX_VALUE));}
}
