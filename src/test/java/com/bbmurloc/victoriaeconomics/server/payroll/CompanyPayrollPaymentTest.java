package com.bbmurloc.victoriaeconomics.server.payroll;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.production.port.BuildingPayrollPort.State;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.company.domain.*;
import com.bbmurloc.victoriaeconomics.server.region.domain.*;
import com.bbmurloc.victoriaeconomics.server.workforce.domain.RegionalLaborSupply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanyPayrollPaymentTest {
    @TempDir Path dir;Path file(){return dir.resolve("payments.sqlite");}
    @Test void realTransferSettlesSingleBuildingAndCanBeReplayedWithoutNewMoney()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            UUID building=w.building(3,7),source=w.companyAccount(),target=w.recipient();w.fund(source,100);w.mature(1);
            assertEquals(State.ARREARS,w.f.query.state(building));assertEquals(0,w.f.accounts.load(target).balance());
            var result=w.pay(1);assertEquals(PayrollPaymentRun.Status.COMPLETED,result.status());assertEquals(21,result.lines().getFirst().amount());
            assertEquals(79,w.f.accounts.load(source).balance());assertEquals(21,w.f.accounts.load(target).balance());assertEquals(21,w.scalar("SELECT SUM(paid) FROM payroll_liabilities"));assertEquals(State.CURRENT,w.f.query.state(building));
            w.fund(source,10);assertEquals(result,w.payments.pay(w.command(1)));assertEquals(89,w.f.accounts.load(source).balance());assertEquals(Optional.of(result),w.payments.verify(w.command(1)));
            w.periods.recoverAcceptedTime();assertEquals(1,w.payments.processedPeriod());
        }
        try(var c=SqliteEconomyDatabase.open(file())){assertNotNull(c);}
    }
    @Test void twoBuildingsShareInsufficientCashByLargestRemainderWithStableTie()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            UUID low=new UUID(0,1),high=UUID.fromString("80000000-0000-0000-0000-000000000001");
            w.building(low,TestIdentities.COMPANY,TestIdentities.REGION,FinancialTestWorld.CURRENCY,1,1);w.building(high,TestIdentities.COMPANY,TestIdentities.REGION,FinancialTestWorld.CURRENCY,1,1);
            UUID source=w.companyAccount(),target=w.recipient();w.fund(source,1);w.mature(1);var result=w.pay(1);
            var values=new HashMap<UUID,Long>();result.lines().forEach(l->values.put(l.building(),l.amount()));assertEquals(Map.of(low,1L,high,0L),values);
            assertEquals(0,w.f.accounts.load(source).balance());assertEquals(1,w.f.accounts.load(target).balance());assertEquals(1,w.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));assertTrue(w.payments.verify(w.command(1)).isPresent());
        }
    }
    @Test void zeroCashCompletesOnlyOneZeroDecisionButAccrualContinues()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(2,3);w.companyAccount();UUID target=w.recipient();w.mature(1);var result=w.pay(1);w.periods.recoverAcceptedTime();
            assertEquals(0,result.partitions().getFirst().paid());assertEquals(0,w.f.accounts.load(target).balance());assertEquals(6,w.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));
            w.mature(2);w.periods.recoverAcceptedTime();assertEquals(12,w.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));assertEquals(2,w.payments.processedPeriod());assertEquals(2,w.scalar("SELECT COUNT(*) FROM payroll_payment_runs"));
        }
    }
    @Test void reservedCompanyCashIsExcludedAndItsEvidenceSurvivesRelease()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(2,5);UUID source=w.companyAccount();w.recipient();w.fund(source,10);var reservation=new CashReservation(UUID.randomUUID(),source,7,"explicit isolated market lock",true);w.f.finance.reserve(reservation);w.mature(1);
            var run=w.pay(1);assertEquals(3,run.partitions().getFirst().paid());assertEquals(7,run.partitions().getFirst().reservedCash());assertEquals(7,w.f.accounts.load(source).reserved());
            w.f.finance.release(reservation.id(),source);assertTrue(w.payments.verify(w.command(1)).isPresent());assertEquals(run,w.payments.pay(w.command(1)));assertEquals(7,w.f.accounts.load(source).balance());
        }
    }
    @Test void missingCompanyOrHouseholdAccountLeavesIntentAndNoFakeSuccess()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,1);w.mature(1);w.payments.preparePeriod(1);
            assertThrows(IllegalStateException.class,()->w.payments.pay(w.command(1)));w.recipient();assertThrows(IllegalStateException.class,()->w.payments.pay(w.command(1)));
            assertEquals(2,w.scalar("SELECT COUNT(*) FROM payroll_payment_failures"));assertEquals(0,w.scalar("SELECT SUM(paid) FROM payroll_accruals"));assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_lines"));
            w.companyAccount();var result=w.payments.pay(w.command(1));assertEquals(PayrollPaymentRun.Status.COMPLETED,result.status());assertEquals(2,w.scalar("SELECT COUNT(*) FROM payroll_payment_failures"));
        }
    }
    @Test void paymentRequiresAcceptedCompletedAccrualAndMaturity()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,1);w.companyAccount();w.recipient();assertThrows(IllegalStateException.class,()->w.payments.preparePeriod(1));w.f.acceptedTimeFixture(1200);
            assertThrows(IllegalStateException.class,()->w.payments.preparePeriod(1));assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_cycles"));
        }
    }
    @Test void partialMultiplePeriodsAreGatedUntilFullPaymentMakesTheSplitUnambiguous()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,5);UUID source=w.companyAccount(),target=w.recipient();w.mature(1);w.periods.recoverAcceptedTime();w.mature(2);w.fund(source,5);w.payments.preparePeriod(2);
            assertThrows(IllegalStateException.class,()->w.payments.pay(w.command(2)));assertEquals(5,w.f.accounts.load(source).balance());assertEquals(0,w.f.accounts.load(target).balance());assertEquals(0,w.scalar("SELECT SUM(paid) FROM payroll_accruals"));
            w.fund(source,5);var result=w.payments.pay(w.command(2));assertEquals(List.of(5L,5L),result.lines().stream().map(PayrollPaymentLine::amount).toList());assertEquals(10,w.f.accounts.load(target).balance());assertTrue(w.payments.verify(w.command(2)).isPresent());
        }
    }
    @Test void allBuildingsCanBePaidInFullWithoutLosingOrCreatingCurrency()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(2,3);w.building(3,3);UUID source=w.companyAccount(),target=w.recipient();w.fund(source,20);w.mature(1);var run=w.pay(1);
            assertEquals(15,run.lines().stream().mapToLong(PayrollPaymentLine::amount).sum());assertEquals(5,w.f.accounts.load(source).balance());assertEquals(15,w.f.accounts.load(target).balance());assertEquals(15,w.scalar("SELECT SUM(paid) FROM payroll_accruals"));assertTrue(w.payments.verify(w.command(1)).isPresent());
        }
    }
    @Test void allCurrencyPartitionsHaveOneCompanyPeriodParentAndNoFx()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            var secondCurrency=new CurrencyId(new UUID(701,1));var secondRegion=new RegionId(new UUID(201,1));
            w.building(2,3);w.building(UUID.randomUUID(),TestIdentities.COMPANY,secondRegion,secondCurrency,3,7);
            UUID a=w.companyAccount(),b=w.companyAccount(TestIdentities.COMPANY,secondCurrency),x=w.recipient(),y=w.recipient(secondRegion,secondCurrency);
            w.fund(a,6);w.f.fundingFixture(b,10,secondCurrency);w.mature(1);var run=w.pay(1);
            assertEquals(2,run.partitions().size());assertEquals(1,w.scalar("SELECT COUNT(*) FROM payroll_payment_runs"));assertEquals(6,w.f.accounts.load(x).balance());assertEquals(10,w.f.accounts.load(y).balance());assertEquals(11,w.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));
            assertTrue(w.payments.verify(w.command(1)).isPresent());assertEquals(run,w.payments.pay(w.command(1)));
            assertThrows(IllegalArgumentException.class,()->w.f.finance.transfer(new TransferReceipt(UUID.randomUUID(),a,y,FinancialTestWorld.CURRENCY,1)));
        }
    }
    @Test void aMissingCurrencyPartitionPreventsAnyOtherPartitionFromPosting()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            var currency=new CurrencyId(new UUID(701,1));var region=new RegionId(new UUID(201,1));
            w.building(1,1);w.building(UUID.randomUUID(),TestIdentities.COMPANY,region,currency,1,1);UUID source=w.companyAccount();w.recipient();w.recipient(region,currency);w.fund(source,10);w.mature(1);
            assertThrows(IllegalStateException.class,()->w.pay(1));assertEquals(10,w.f.accounts.load(source).balance());assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_lines"));assertEquals(0,w.scalar("SELECT SUM(paid) FROM payroll_accruals"));
        }
    }
    /** Isolated history fixture only. No sale/relocation entry point is added to the server. */
    void historicalSegmentFixture(PaymentTestWorld w,UUID building,CompanyId company,RegionId region)throws Exception{
        if(w.f.world.companies.find(company).isEmpty())w.f.world.companies.create(new Company(company));
        if(w.f.world.regions.find(region).isEmpty()){
            w.f.world.regions.create(new Region(region));w.f.accrual.configureRate(new RegionalWageRate(region,FinancialTestWorld.CURRENCY,1200,1,0));
            SqliteTransactions.write(w.c,tx->{w.f.world.supply.inTransaction(tx).create(new RegionalLaborSupply(region,"laborer",100,0));return null;});
        }
        SqliteTransactions.write(w.c,tx->{
            var p=w.f.payroll.inTransaction(tx);var old=p.currentForBuilding(building);p.close(old.id(),1200);
            try(var s=w.c.prepareStatement("UPDATE economic_buildings SET owner_company_id=?,region_id=?,identity_revision=identity_revision+1 WHERE id=?")){
                s.setString(1,company.toString());s.setString(2,region.toString());s.setString(3,building.toString());s.executeUpdate();
            }
            p.enroll(UUID.randomUUID(),building,FinancialTestWorld.CURRENCY);return null;
        });
    }
    @Test void multipleHistoricalBeneficiariesAreRetainedAndOnlyAnUnambiguousFullSplitCanPay()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            UUID building=w.building(1,1),source=w.companyAccount(),original=w.recipient();w.mature(1);w.periods.recoverAcceptedTime();
            var region=new RegionId(new UUID(201,1));historicalSegmentFixture(w,building,TestIdentities.COMPANY,region);UUID next=w.recipient(region,FinancialTestWorld.CURRENCY);
            w.mature(2);w.fund(source,1);assertThrows(IllegalStateException.class,()->w.pay(2));assertEquals(0,w.f.accounts.load(original).balance());assertEquals(0,w.f.accounts.load(next).balance());
            w.fund(source,1);var run=w.payments.pay(w.command(2));assertEquals(2,run.lines().size());assertEquals(1,w.f.accounts.load(original).balance());assertEquals(1,w.f.accounts.load(next).balance());assertTrue(w.payments.verify(w.command(2)).isPresent());
        }
    }
    @Test void historicalDebtorCannotBeMixedIntoNewCompanyPaymentOrCurrentBuildingArrears()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            UUID building=w.building(1,1),oldSource=w.companyAccount();w.recipient();w.mature(1);w.periods.recoverAcceptedTime();
            var buyer=new CompanyId(new UUID(101,1));historicalSegmentFixture(w,building,buyer,TestIdentities.REGION);UUID source=w.companyAccount(buyer,FinancialTestWorld.CURRENCY);w.fund(source,1);w.mature(2);w.periods.recoverAcceptedTime();
            assertEquals(0,w.f.accounts.load(oldSource).balance());assertEquals(1,w.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));assertEquals(State.CURRENT,w.f.query.state(building));
            var buyerRun=w.payments.verify(PayrollPaymentRun.Command.forPeriod(buyer,2)).orElseThrow();assertEquals(1,buyerRun.lines().getFirst().amount());assertEquals(1,buyerRun.lines().size());
        }
    }
    @Test void destinationOverflowRollsBackRealCash()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,1);UUID source=w.companyAccount(),target=w.recipient();w.fund(source,1);w.fund(target,Long.MAX_VALUE);w.mature(1);
            assertThrows(ArithmeticException.class,()->w.pay(1));assertEquals(1,w.f.accounts.load(source).balance());assertEquals(Long.MAX_VALUE,w.f.accounts.load(target).balance());assertEquals(0,w.scalar("SELECT SUM(paid) FROM payroll_accruals"));
        }
    }
    @Test void wageVersionOverflowCannotDebitCashOrPartiallyReduceDebt()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,1);UUID source=w.companyAccount(),target=w.recipient();w.fund(source,1);w.mature(1);w.sql("UPDATE payroll_accruals SET revision=9223372036854775807");
            assertThrows(ArithmeticException.class,()->w.pay(1));assertEquals(1,w.f.accounts.load(source).balance());assertEquals(0,w.f.accounts.load(target).balance());assertEquals(0,w.scalar("SELECT SUM(paid) FROM payroll_liabilities"));
        }
    }
    @Test void currencyDebtTotalOverflowIsRejectedWithoutRounding()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,Long.MAX_VALUE);w.building(1,Long.MAX_VALUE);UUID source=w.companyAccount();w.recipient();w.fund(source,1);w.mature(1);
            assertThrows(ArithmeticException.class,()->w.pay(1));assertEquals(1,w.f.accounts.load(source).balance());assertEquals(0,w.scalar("SELECT COUNT(*) FROM payroll_payment_lines"));
        }
    }
    @Test void anUnresolvedPartialSplitIsDurablyBlockedWhileFutureWagesStillAccrue()throws Exception{
        try(var w=new PaymentTestWorld(file())){
            w.building(1,5);UUID source=w.companyAccount();w.recipient();w.mature(1);w.periods.recoverAcceptedTime();w.fund(source,1);
            w.mature(2);w.periods.recoverAcceptedTime();assertEquals(PayrollPaymentRun.Status.BLOCKED,w.payments.verify(w.command(2)).orElseThrow().status());assertEquals(2,w.payments.processedPeriod());assertEquals(10,w.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));
            w.mature(3);w.periods.recoverAcceptedTime();assertEquals(15,w.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));assertEquals(3,w.payments.processedPeriod());assertEquals(1,w.f.accounts.load(source).balance());
            // Older rejected decisions stay sealed once a newer period exists; the new period includes all debt.
            assertEquals(PayrollPaymentRun.Status.BLOCKED,w.payments.pay(w.command(2)).status());w.fund(source,14);assertEquals(PayrollPaymentRun.Status.COMPLETED,w.payments.pay(w.command(3)).status());assertEquals(0,w.scalar("SELECT SUM(amount-paid) FROM payroll_liabilities"));assertEquals(PayrollPaymentRun.Status.BLOCKED,w.payments.verify(w.command(2)).orElseThrow().status());
        }
    }
}
