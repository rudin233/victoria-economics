package com.bbmurloc.victoriaeconomics.server.payroll.port;

import com.bbmurloc.victoriaeconomics.server.payroll.domain.PayrollPaymentRun;
import java.util.Optional;
import java.util.List;

public interface PayrollPaymentRepository {
    Optional<PayrollPaymentRun> find(PayrollPaymentRun.Command command);
    Optional<PayrollPaymentRun> verify(PayrollPaymentRun.Command command);
    long processedPeriod();
    List<PayrollPaymentRun.Failure> failures(PayrollPaymentRun.Command command);
}
