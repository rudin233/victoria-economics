package com.bbmurloc.victoriaeconomics.server.payroll.application;

import com.bbmurloc.victoriaeconomics.server.storage.sqlite.CommitOutcomeUnknownException;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.PayrollPaymentUnavailable;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.Connection;
import java.util.ArrayList;

/** Accepted-time scheduling only: accrue/mature first, settle companies, then acknowledge the period. */
public final class PayrollPeriodService {
    private static final System.Logger LOG=System.getLogger(PayrollPeriodService.class.getName());
    private final Object lock;private final CompanyPayrollService payments;private final SqliteEconomicTimeRepository time;
    public PayrollPeriodService(Connection c,Object lock){this.lock=lock;payments=new CompanyPayrollService(c,lock);time=new SqliteEconomicTimeRepository(c,lock);}
    public void recoverAcceptedTime(){synchronized(lock){
        var accepted=time.load();long done=payments.processedPeriod();
        while(done<accepted.periodId()){
            long period=Math.incrementExact(done);payments.preparePeriod(period);var failures=new ArrayList<RuntimeException>();
            for(var command:payments.commands(period))try{payments.pay(command);}
            catch(CommitOutcomeUnknownException unknown){throw unknown;}
            catch(PayrollPaymentUnavailable knownRejection){LOG.log(System.Logger.Level.WARNING,"Company payroll blocked at "+command.company()+" period "+period+": "+knownRejection.getMessage());}
            catch(RuntimeException failure){failures.add(failure);}
            if(!failures.isEmpty()){
                var incomplete=new IllegalStateException("Payroll payment cycle remains pending: "+period);failures.forEach(incomplete::addSuppressed);throw incomplete;
            }
            payments.finishPeriod(period);done=period;
        }
    }}
}
