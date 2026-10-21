package com.bbmurloc.victoriaeconomics.server.payroll.domain;

import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A company-period decision owns all currency partitions and payment lines. No writable line aggregate. */
public record PayrollPaymentRun(Command command,Status status,List<Partition> partitions,List<PayrollPaymentLine> lines,long revision) {
    public enum Status { PENDING,BLOCKED,COMPLETED }
    public record Failure(long attempt,long observedTick,String reason) {
        public Failure {if(attempt<=0||observedTick<0||reason==null||reason.isBlank())throw new IllegalArgumentException("Invalid payment failure evidence");}
    }
    public record Command(UUID id,CompanyId company,long periodId) {
        public Command {
            Objects.requireNonNull(id);Objects.requireNonNull(company);
            if(periodId<=0)throw new IllegalArgumentException("Positive payroll period required");
            Math.multiplyExact(periodId,1200L);
            if(!id.equals(identity(company,periodId)))throw new IllegalArgumentException("Company-period payment identity conflicts with command");
        }
        public static Command forPeriod(CompanyId company,long period){return new Command(identity(company,period),company,period);}
        public long cutoffTick(){return Math.multiplyExact(periodId,1200L);}
        private static UUID identity(CompanyId company,long period){return stable("payroll-run:"+company+":"+period);}
    }
    public record Partition(CurrencyId currency,UUID sourceAccount,long cashBalance,long reservedCash,long ledgerSequence,long totalDue,long paid) {
        public Partition {
            Objects.requireNonNull(currency);Objects.requireNonNull(sourceAccount);
            if(cashBalance<0||reservedCash<0||reservedCash>cashBalance||ledgerSequence<=0||totalDue<=0||paid!=Math.min(Math.subtractExact(cashBalance,reservedCash),totalDue))throw new IllegalArgumentException("Invalid currency partition");
        }
        public long availableCash(){return Math.subtractExact(cashBalance,reservedCash);}
    }
    public PayrollPaymentRun {
        Objects.requireNonNull(command);Objects.requireNonNull(status);partitions=List.copyOf(partitions);lines=List.copyOf(lines);
        if(revision<0||(status!=Status.COMPLETED&&(!partitions.isEmpty()||!lines.isEmpty()))
                ||(status==Status.COMPLETED&&(partitions.isEmpty()||lines.isEmpty())))throw new IllegalArgumentException("Invalid payment run state");
    }
    public static UUID transferId(UUID run,UUID segment,long liabilityPeriod){return stable("payroll-transfer:"+run+":"+segment+":"+liabilityPeriod);}
    private static UUID stable(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
}
