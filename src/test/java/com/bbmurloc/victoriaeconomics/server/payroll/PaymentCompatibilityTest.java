package com.bbmurloc.victoriaeconomics.server.payroll;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.financial.FinancialTestWorld;
import com.bbmurloc.victoriaeconomics.server.finance.domain.CashReservation;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentCompatibilityTest {
    @TempDir Path dir;Path file(){return dir.resolve("contract.sqlite");}
    void complete(PaymentTestWorld w)throws Exception{
        w.building(1,5);UUID source=w.companyAccount();w.recipient();w.fund(source,6);w.f.finance.reserve(new CashReservation(UUID.randomUUID(),source,1,"audited locked cash",true));w.mature(1);w.periods.recoverAcceptedTime();
        long revision=w.households.budget(TestIdentities.REGION,FinancialTestWorld.CURRENCY).revision();w.households.allocate(TestIdentities.REGION,FinancialTestWorld.CURRENCY,3,2,revision);
    }
    @Test void fullV8PaymentLedgerAndBudgetReopenReadOnlyWithoutAnyByteRewrite()throws Exception{
        try(var w=new PaymentTestWorld(file())){complete(w);assertTrue(w.payments.verify(w.command(1)).isPresent());}
        byte[] before=Files.readAllBytes(file());try(var c=SqliteEconomyDatabase.open(file())){assertNotNull(c);}assertArrayEquals(before,Files.readAllBytes(file()));
    }
    @ParameterizedTest @ValueSource(strings={"paidWithoutLines","paidCounterMismatch","missingTransfer","wrongTransfer","wrongBeneficiary","wrongSource","overpay","cashSnapshot","missingLockSnapshot","budgetOverCash","missingBudget","cycleAhead","wrongParentIdentity","lineGap","lineOverflow","pendingPosted","missingParent"})
    void inconsistentPaymentOriginsAndBudgetsAreRejectedWithoutRepair(String mutation)throws Exception{
        try(var w=new PaymentTestWorld(file())){
            complete(w);w.sql("PRAGMA foreign_keys=OFF");w.sql("PRAGMA ignore_check_constraints=ON");
            switch(mutation){
                case "paidWithoutLines"->w.sql("DELETE FROM payroll_payment_lines");
                case "paidCounterMismatch"->{w.sql("UPDATE payroll_accruals SET paid=4");w.sql("UPDATE payroll_liabilities SET paid=4");}
                case "missingTransfer"->w.sql("DELETE FROM finance_events WHERE kind='TRANSFER'");
                case "wrongTransfer"->w.sql("UPDATE finance_events SET amount=4 WHERE kind='TRANSFER'");
                case "wrongBeneficiary"->w.sql("UPDATE payroll_payment_lines SET recipient_account=(SELECT source_account FROM payroll_payment_partitions)");
                case "wrongSource"->w.sql("UPDATE payroll_payment_partitions SET source_account=(SELECT recipient_account FROM payroll_payment_lines)");
                case "overpay"->w.sql("UPDATE payroll_payment_lines SET amount=6");
                case "cashSnapshot"->w.sql("UPDATE payroll_payment_partitions SET cash_balance=7");
                case "missingLockSnapshot"->w.sql("DELETE FROM payroll_payment_cash_locks");
                case "budgetOverCash"->w.sql("UPDATE regional_household_budgets SET consumption=6,savings=0");
                case "missingBudget"->w.sql("DELETE FROM regional_household_budgets");
                case "cycleAhead"->w.sql("UPDATE payroll_payment_runtime SET processed_period=2,revision=2");
                case "wrongParentIdentity"->w.sql("UPDATE payroll_payment_runs SET id='"+UUID.randomUUID()+"'");
                case "lineGap"->w.sql("UPDATE payroll_payment_lines SET line=2");
                case "lineOverflow"->w.sql("UPDATE payroll_payment_lines SET line=4294967296");
                case "pendingPosted"->w.sql("UPDATE payroll_payment_runs SET status='PENDING',decided_tick=NULL,revision=0");
                default->w.sql("DELETE FROM payroll_payment_runs");
            }
        }
        byte[] before=Files.readAllBytes(file());assertThrows(IllegalStateException.class,()->SqliteEconomyDatabase.open(file()));assertArrayEquals(before,Files.readAllBytes(file()));
    }
    @Test void paidDataRejectionDoesNotCheckpointOrModifyExistingWal()throws Exception{
        try(var w=new PaymentTestWorld(file())){complete(w);}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file());var s=c.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA wal_autocheckpoint=0");s.execute("UPDATE payroll_liabilities SET paid=4");s.execute("UPDATE payroll_accruals SET paid=4");
            byte[] main=Files.readAllBytes(file()),wal=Files.readAllBytes(Path.of(file()+"-wal"));assertThrows(IllegalStateException.class,()->SqliteEconomyDatabase.open(file()));assertArrayEquals(main,Files.readAllBytes(file()));assertArrayEquals(wal,Files.readAllBytes(Path.of(file()+"-wal")));
        }
    }
}
