package com.bbmurloc.victoriaeconomics.server.payroll.infrastructure;

import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.payroll.port.PayrollPaymentRepository;
import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.household.infrastructure.SqliteHouseholdBudgetRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.*;
import java.util.*;

/** Parent company-period intent and one atomic posting, using the existing WriteSession only. */
public final class SqlitePayrollPaymentRepository implements PayrollPaymentRepository {
    private final Connection connection;private final Object lock;
    private final SqliteFinanceAccountRepository finance;
    private final SqlitePayrollRepository payroll;
    private final SqliteHouseholdBudgetRepository households;
    public SqlitePayrollPaymentRepository(Connection c,Object lock){connection=c;this.lock=lock;finance=new SqliteFinanceAccountRepository(c,lock);payroll=new SqlitePayrollRepository(c,lock);households=new SqliteHouseholdBudgetRepository(c,lock);}
    @Override public Optional<PayrollPaymentRun> find(PayrollPaymentRun.Command command){synchronized(lock){return SqliteTransactions.read(connection,tx->read(command));}}
    @Override public Optional<PayrollPaymentRun> verify(PayrollPaymentRun.Command command){synchronized(lock){return SqliteTransactions.read(connection,tx->{EconomyPaymentValidation.validate(connection,tx);return read(command);});}}
    @Override public long processedPeriod(){synchronized(lock){return SqliteTransactions.read(connection,tx->processed());}}
    @Override public List<PayrollPaymentRun.Failure> failures(PayrollPaymentRun.Command command){synchronized(lock){return SqliteTransactions.read(connection,tx->{
        read(command).orElseThrow(()->new IllegalStateException("Payment run missing"));var result=new ArrayList<PayrollPaymentRun.Failure>();
        try(var s=connection.prepareStatement("SELECT * FROM payroll_payment_failures WHERE run_id=? ORDER BY attempt")){
            s.setString(1,command.id().toString());try(var r=s.executeQuery()){while(r.next())result.add(new PayrollPaymentRun.Failure(r.getLong("attempt"),r.getLong("observed_tick"),r.getString("reason")));}
        }
        return List.copyOf(result);
    });}}
    private long processed()throws SQLException {try(var s=connection.createStatement();var r=s.executeQuery("SELECT processed_period FROM payroll_payment_runtime WHERE id=1")){if(!r.next())throw new IllegalStateException("Payment runtime missing");return r.getLong(1);}}
    public Optional<PayrollPaymentRun> inSnapshot(SqliteTransactions.ReadSession tx,PayrollPaymentRun.Command command)throws SQLException {tx.requireConnection(connection);return read(command);}
    private Optional<PayrollPaymentRun> read(PayrollPaymentRun.Command command)throws SQLException {
        String status;long revision;
        try(var s=connection.prepareStatement("SELECT * FROM payroll_payment_runs WHERE id=? OR (company_id=? AND period_id=?)")){
            s.setString(1,command.id().toString());s.setString(2,command.company().toString());s.setLong(3,command.periodId());
            try(var r=s.executeQuery()){
                if(!r.next())return Optional.empty();
                if(!r.getString("id").equals(command.id().toString())||!r.getString("company_id").equals(command.company().toString())||r.getLong("period_id")!=command.periodId())throw new IllegalArgumentException("Company-period command conflicts with durable identity");
                status=r.getString("status");revision=r.getLong("revision");if(r.next())throw new IllegalStateException("Duplicate company-period parent");
            }
        }
        var partitions=new ArrayList<PayrollPaymentRun.Partition>();var lines=new ArrayList<PayrollPaymentLine>();
        try(var s=connection.prepareStatement("SELECT * FROM payroll_payment_partitions WHERE run_id=? ORDER BY currency_id")){
            s.setString(1,command.id().toString());try(var r=s.executeQuery()){while(r.next())partitions.add(new PayrollPaymentRun.Partition(CurrencyId.parse(r.getString("currency_id")),UUID.fromString(r.getString("source_account")),r.getLong("cash_balance"),r.getLong("reserved_cash"),r.getLong("ledger_sequence"),r.getLong("total_due"),r.getLong("paid")));}
        }
        try(var s=connection.prepareStatement("SELECT * FROM payroll_payment_lines WHERE run_id=? ORDER BY line")){
            s.setString(1,command.id().toString());try(var r=s.executeQuery()){while(r.next())lines.add(new PayrollPaymentLine(UUID.fromString(r.getString("segment_id")),r.getLong("liability_period"),UUID.fromString(r.getString("building_id")),RegionId.parse(r.getString("region_id")),CurrencyId.parse(r.getString("currency_id")),UUID.fromString(r.getString("recipient_account")),r.getLong("debt_before"),r.getLong("amount")));}
        }
        return Optional.of(new PayrollPaymentRun(command,PayrollPaymentRun.Status.valueOf(status),partitions,lines,revision));
    }
    public List<PayrollPaymentRun.Command> commands(long period){synchronized(lock){return SqliteTransactions.read(connection,tx->{
        var result=new ArrayList<PayrollPaymentRun.Command>();
        try(var s=connection.prepareStatement("SELECT id,company_id FROM payroll_payment_runs WHERE period_id=? ORDER BY company_id")){
            s.setLong(1,period);try(var r=s.executeQuery()){while(r.next())result.add(new PayrollPaymentRun.Command(UUID.fromString(r.getString(1)),CompanyId.parse(r.getString(2)),period));}
        }
        return List.copyOf(result);
    });}}
    public Session inTransaction(SqliteTransactions.WriteSession tx){tx.requireConnection(connection);return new Session(tx);}
    public final class Session {
        private final SqliteTransactions.WriteSession tx;
        private Session(SqliteTransactions.WriteSession tx){this.tx=tx;}
        private void ready(long period)throws SQLException {
            long boundary=Math.multiplyExact(period,1200L);var time=SqliteEconomicTimeRepository.inTransaction(connection,tx);
            if(period<=0||boundary>time.acceptedTick()||!time.payrollCaughtUp())throw new IllegalStateException("Accepted payroll accrual/maturity must precede payment");
            try(var s=connection.prepareStatement("SELECT 1 FROM payroll_accruals WHERE last_accrued_tick<?")){
                s.setLong(1,boundary);try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("Unaccrued wage segment at payment cutoff");}
            }
        }
        public void preparePeriod(long period)throws SQLException {tx.perform(()->{
            ready(period);long before=processed();
            if(period<=before)return null;
            if(period!=Math.incrementExact(before))throw new IllegalStateException("Complete preceding payment cycle first");
            try(var s=connection.prepareStatement("SELECT 1 FROM payroll_payment_cycles WHERE period_id=?")){
                s.setLong(1,period);try(var r=s.executeQuery()){if(r.next())return null;}
            }
            try(var s=connection.prepareStatement("INSERT INTO payroll_payment_cycles VALUES(?,?,'PLANNED',0)")){
                s.setLong(1,period);s.setLong(2,Math.multiplyExact(period,1200L));s.executeUpdate();
            }
            var companies=new ArrayList<CompanyId>();
            try(var s=connection.prepareStatement("SELECT DISTINCT p.company_id FROM payroll_accruals p JOIN payroll_liabilities l ON l.segment_id=p.id WHERE l.period_id<=? AND l.paid<l.amount ORDER BY p.company_id")){
                s.setLong(1,period);try(var r=s.executeQuery()){while(r.next())companies.add(CompanyId.parse(r.getString(1)));}
            }
            try(var s=connection.prepareStatement("INSERT INTO payroll_payment_runs VALUES(?,?,?,'PENDING',NULL,0)")){
                for(var company:companies){var command=PayrollPaymentRun.Command.forPeriod(company,period);s.setString(1,command.id().toString());s.setString(2,company.toString());s.setLong(3,period);s.executeUpdate();}
            }
            return null;
        });}
        public PayrollPaymentRun complete(PayrollPaymentRun.Command command)throws SQLException {return tx.perform(()->{
            var prior=read(command).orElseThrow(()->new IllegalStateException("Prepare durable company-period intent before payment"));
            if(prior.status()==PayrollPaymentRun.Status.COMPLETED)return prior;
            var time=SqliteEconomicTimeRepository.inTransaction(connection,tx);
            // A sealed older rejection cannot compete with a newer period's all-debt decision.
            if(prior.status()==PayrollPaymentRun.Status.BLOCKED&&time.periodId()>command.periodId())return prior;
            ready(command.periodId());long cursor=processed();
            if(cursor!=command.periodId()-1&&!(prior.status()==PayrollPaymentRun.Status.BLOCKED&&cursor==command.periodId()))throw new IllegalStateException("Payment cycle is not current");
            if(prior.status()==PayrollPaymentRun.Status.BLOCKED)try(var s=connection.prepareStatement("UPDATE payroll_payment_runs SET status='PENDING' WHERE id=? AND status='BLOCKED'")){
                s.setString(1,command.id().toString());if(s.executeUpdate()!=1)throw new IllegalStateException("Blocked run changed");
            }
            var cash=finance.inTransaction(tx);var household=households.inTransaction(tx);var sources=new TreeMap<String,List<PayrollPaymentLine>>();
            try(var s=connection.prepareStatement("SELECT p.*,l.period_id,l.amount-l.paid AS unpaid FROM payroll_accruals p JOIN payroll_liabilities l ON l.segment_id=p.id WHERE p.company_id=? AND l.period_id<=? AND l.paid<l.amount ORDER BY p.currency_id,p.building_id,p.id,l.period_id")){
                s.setString(1,command.company().toString());s.setLong(2,command.periodId());try(var r=s.executeQuery()){while(r.next()){
                    var currency=CurrencyId.parse(r.getString("currency_id"));var region=RegionId.parse(r.getString("region_id"));
                    UUID recipient=household.find(region,currency).orElseThrow(()->new PayrollPaymentUnavailable("Explicit household account/sector registration required")).sector().accountId();
                    var line=new PayrollPaymentLine(UUID.fromString(r.getString("id")),r.getLong("period_id"),UUID.fromString(r.getString("building_id")),region,currency,recipient,r.getLong("unpaid"),0);
                    sources.computeIfAbsent(currency.toString(),key->new ArrayList<>()).add(line);
                }}
            }
            if(sources.isEmpty())throw new IllegalStateException("Prepared payment sources disappeared; verify durable decision");
            var partitions=new ArrayList<PayrollPaymentRun.Partition>();var planned=new ArrayList<PayrollPaymentLine>();
            for(var entry:sources.entrySet()){
                var currency=CurrencyId.parse(entry.getKey());var source=cash.find(FinanceAccount.OwnerKind.COMPANY,command.company().value(),currency).orElseThrow(()->new PayrollPaymentUnavailable("Explicit company account required for "+currency));
                long total=0;for(var line:entry.getValue())total=Math.addExact(total,line.debtBefore());
                long sequence;try(var s=connection.prepareStatement("SELECT MAX(sequence) FROM finance_ledger WHERE account_id=?")){
                    s.setString(1,source.id().toString());try(var r=s.executeQuery()){r.next();sequence=r.getLong(1);}
                }
                partitions.add(new PayrollPaymentRun.Partition(currency,source.id(),source.balance(),source.reserved(),sequence,total,Math.min(total,source.available())));
                planned.addAll(PayrollPaymentPlanner.allocate(entry.getValue(),source.available()));
            }
            // Plan every currency before changing cash. One failing partition rolls the entire parent back.
            for(var part:partitions){
                try(var s=connection.prepareStatement("INSERT INTO payroll_payment_partitions VALUES(?,?,?,?,?,?,?,?)")){
                    s.setString(1,command.id().toString());s.setString(2,part.currency().toString());s.setString(3,part.sourceAccount().toString());s.setLong(4,part.cashBalance());s.setLong(5,part.reservedCash());s.setLong(6,part.ledgerSequence());s.setLong(7,part.totalDue());s.setLong(8,part.paid());s.executeUpdate();
                }
                try(var s=connection.prepareStatement("INSERT INTO payroll_payment_cash_locks SELECT ?,?,id FROM cash_reservations WHERE account_id=? AND active=1")){
                    s.setString(1,command.id().toString());s.setString(2,part.currency().toString());s.setString(3,part.sourceAccount().toString());s.executeUpdate();
                }
            }
            int number=0;var wages=payroll.inTransaction(tx);
            for(var line:planned){
                var part=partitions.stream().filter(p->p.currency().equals(line.currency())).findFirst().orElseThrow();
                String event=null;if(line.amount()>0){var transfer=cash.transfer(line.transfer(command.id(),part.sourceAccount()));event="transfer:"+transfer.id();}
                try(var s=connection.prepareStatement("INSERT INTO payroll_payment_lines VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
                    s.setString(1,command.id().toString());s.setInt(2,number++);s.setString(3,line.segment().toString());s.setLong(4,line.liabilityPeriod());s.setString(5,line.building().toString());s.setString(6,line.region().toString());s.setString(7,line.currency().toString());s.setString(8,line.recipientAccount().toString());s.setLong(9,line.debtBefore());s.setLong(10,line.amount());s.setString(11,event);s.executeUpdate();
                }
                if(line.amount()>0)wages.recordPayment(command,line);
            }
            try(var s=connection.prepareStatement("UPDATE payroll_payment_runs SET status='COMPLETED',decided_tick=?,revision=1 WHERE id=? AND status='PENDING' AND revision=0")){
                s.setLong(1,SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick());s.setString(2,command.id().toString());if(s.executeUpdate()!=1)throw new IllegalStateException("Payment parent CAS conflict");
            }
            return read(command).orElseThrow();
        });}
        public void recordFailure(PayrollPaymentRun.Command command,String reason,boolean blocked)throws SQLException {tx.perform(()->{
            if(read(command).isEmpty())throw new IllegalStateException("Payment intent missing");
            long attempt=0;try(var s=connection.prepareStatement("SELECT MAX(attempt) FROM payroll_payment_failures WHERE run_id=?")){s.setString(1,command.id().toString());try(var r=s.executeQuery()){r.next();attempt=r.getLong(1);}}
            try(var s=connection.prepareStatement("INSERT INTO payroll_payment_failures VALUES(?,?,?,?)")){
                s.setString(1,command.id().toString());s.setLong(2,Math.incrementExact(attempt));s.setLong(3,SqliteEconomicTimeRepository.inTransaction(connection,tx).acceptedTick());s.setString(4,reason);s.executeUpdate();
            }
            if(blocked)try(var s=connection.prepareStatement("UPDATE payroll_payment_runs SET status='BLOCKED' WHERE id=? AND status IN ('PENDING','BLOCKED')")){
                s.setString(1,command.id().toString());s.executeUpdate();
            }
            return null;
        });}
        public void finishPeriod(long period)throws SQLException {tx.perform(()->{
            if(processed()>=period)return null;ready(period);
            if(processed()!=period-1)throw new IllegalStateException("Payment period gap");
            try(var s=connection.prepareStatement("SELECT 1 FROM payroll_payment_runs WHERE period_id=? AND status='PENDING'")){
                s.setLong(1,period);try(var r=s.executeQuery()){if(r.next())throw new IllegalStateException("Unfinished company payment intent");}
            }
            households.inTransaction(tx).carryAll(period);
            try(var s=connection.prepareStatement("UPDATE payroll_payment_cycles SET status='ACKNOWLEDGED',revision=1 WHERE period_id=? AND status='PLANNED' AND revision=0")){
                s.setLong(1,period);if(s.executeUpdate()!=1)throw new IllegalStateException("Payment cycle missing or changed");
            }
            try(var s=connection.prepareStatement("UPDATE payroll_payment_runtime SET processed_period=?,revision=revision+1 WHERE id=1 AND processed_period=? AND revision<9223372036854775807")){
                s.setLong(1,period);s.setLong(2,period-1);if(s.executeUpdate()!=1)throw new IllegalStateException("Payment cursor CAS/overflow conflict");
            }
            return null;
        });}
    }
}
