package com.bbmurloc.victoriaeconomics.server.payroll.application;

import com.bbmurloc.victoriaeconomics.server.payroll.domain.PayrollPaymentRun;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.PayrollPaymentUnavailable;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollPaymentRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.sql.Connection;
import java.util.*;

/** One company-period posting across every currency. No independent per-building payroll timer. */
public final class CompanyPayrollService {
    private final Connection connection;private final Object lock;private final SqlitePayrollPaymentRepository repository;
    public CompanyPayrollService(Connection c,Object lock){connection=c;this.lock=lock;repository=new SqlitePayrollPaymentRepository(c,lock);}
    public void preparePeriod(long period){write(tx->{repository.inTransaction(tx).preparePeriod(period);return null;});}
    public PayrollPaymentRun pay(PayrollPaymentRun.Command command){synchronized(lock){
        try{return write(tx->repository.inTransaction(tx).complete(command));}
        catch(CommitOutcomeUnknownException unknown){throw unknown;}
        catch(RuntimeException failure){
            // A confirmed rollback leaves the durable intent. Preserve failure evidence instead of erasing it.
            try{if(repository.find(command).isPresent())write(tx->{repository.inTransaction(tx).recordFailure(command,failure.toString(),failure instanceof PayrollPaymentUnavailable);return null;});}
            catch(CommitOutcomeUnknownException unknown){unknown.addSuppressed(failure);throw unknown;}
            catch(RuntimeException auditFailure){failure.addSuppressed(auditFailure);}
            throw failure;
        }
    }}
    public Optional<PayrollPaymentRun> verify(PayrollPaymentRun.Command command){return repository.verify(command);}
    public long processedPeriod(){return repository.processedPeriod();}
    public List<PayrollPaymentRun.Failure> failures(PayrollPaymentRun.Command command){return repository.failures(command);}
    List<PayrollPaymentRun.Command> commands(long period){return repository.commands(period);}
    void finishPeriod(long period){write(tx->{repository.inTransaction(tx).finishPeriod(period);return null;});}
    private <T>T write(SqliteTransactions.WriteWork<T> work){synchronized(lock){
        try{return SqliteTransactions.write(connection,work);}catch(CommitOutcomeUnknownException unknown){SqliteConnectionGuard.quarantine(connection,unknown);throw unknown;}
    }}
}
