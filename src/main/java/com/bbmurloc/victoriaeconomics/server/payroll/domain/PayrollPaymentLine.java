package com.bbmurloc.victoriaeconomics.server.payroll.domain;

import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import java.util.*;

/** Frozen source and beneficiary, subordinate to one immutable company-period decision. */
public record PayrollPaymentLine(UUID segment,long liabilityPeriod,UUID building,RegionId region,CurrencyId currency,
                                 UUID recipientAccount,long debtBefore,long amount) {
    public PayrollPaymentLine {
        Objects.requireNonNull(segment);Objects.requireNonNull(building);Objects.requireNonNull(region);Objects.requireNonNull(currency);Objects.requireNonNull(recipientAccount);
        if(liabilityPeriod<=0||debtBefore<=0||amount<0||amount>debtBefore)throw new IllegalArgumentException("Invalid payroll payment line");
    }
    public TransferReceipt transfer(UUID run,UUID source) {
        return new TransferReceipt(PayrollPaymentRun.transferId(run,segment,liabilityPeriod),source,recipientAccount,currency,amount);
    }
}
