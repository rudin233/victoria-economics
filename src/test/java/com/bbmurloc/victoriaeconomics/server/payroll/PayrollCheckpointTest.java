package com.bbmurloc.victoriaeconomics.server.payroll;

import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.payroll.application.CompanyPayrollService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Full historical cash/payment/debt/remainder graph, not a mock payroll state or isolated balance. */
class PayrollCheckpointTest {
    @TempDir Path directory;
    @Test void paymentLinesLedgerDebtAndFractionalRemainderRestoreTogetherAndReplayDoesNotPayAgain() throws Exception {
        Path file = directory.resolve("checkpoint.sqlite"); long sourceBalance, recipientBalance;
        com.bbmurloc.victoriaeconomics.server.payroll.domain.PayrollPaymentRun.Command command;
        try (var w = new PaymentTestWorld(directory.resolve("working.sqlite"))) {
            var source = w.companyAccount(); var recipient = w.recipient(); w.building(3, 7); w.fund(source, 1000);
            w.mature(1); w.periods.recoverAcceptedTime();
            w.f.acceptedTimeFixture(1201); w.f.accrual.recoverAcceptedTime();
            assertEquals(21, w.scalar("SELECT SUM(paid) FROM payroll_accruals"));
            assertEquals(21, w.scalar("SELECT SUM(remainder) FROM payroll_accruals"));
            sourceBalance = w.scalar("SELECT balance FROM finance_accounts WHERE id='" + source + "'");
            recipientBalance = w.scalar("SELECT balance FROM finance_accounts WHERE id='" + recipient + "'");
            assertEquals(1000, Math.addExact(sourceBalance, recipientBalance));
            var image = EconomyCheckpoint.capture(w.c); image.validate();
            var receipt = new EconomyCheckpoint.Receipt(UUID.randomUUID(), UUID.randomUUID(), 1, 1201, image.sha256());
            new SqliteCheckpointStore(file).save(null, receipt, image); command = w.command(1);
        }
        var recovered = new SqliteCheckpointStore(file).load(); assertEquals(1201, recovered.receipt().economicTick());
        try (var c = SqliteEconomyDatabase.open(file)) {
            assertEquals(21, scalar(c, "SELECT SUM(paid) FROM payroll_accruals"));
            assertEquals(21, scalar(c, "SELECT SUM(remainder) FROM payroll_accruals"));
            assertEquals(0, scalar(c, "SELECT SUM(amount-paid) FROM payroll_liabilities"));
            assertTrue(scalar(c, "SELECT COUNT(*) FROM payroll_payment_lines") > 0);
            assertTrue(scalar(c, "SELECT COUNT(*) FROM finance_ledger") > 2);
            long before = scalar(c, "SELECT SUM(balance) FROM finance_accounts");
            new CompanyPayrollService(c, new Object()).pay(command);
            assertEquals(before, scalar(c, "SELECT SUM(balance) FROM finance_accounts"));
            assertEquals(recovered.snapshot().sha256(), EconomyCheckpoint.capture(c).sha256(), "Idempotent replay changed recovered cash/debt/history");
        }
    }
    private static long scalar(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getLong(1); }
    }
}
