package com.bbmurloc.victoriaeconomics.server.payroll.domain;

/** A known planning gate after a confirmed rollback, never an unknown financial outcome. */
public final class PayrollPaymentUnavailable extends IllegalStateException {
    public PayrollPaymentUnavailable(String reason){super(reason);}
}
