package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.company.domain.CompanyId;
import com.bbmurloc.victoriaeconomics.server.region.domain.RegionId;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.household.domain.*;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollPaymentRepository;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.*;
import java.util.*;

/** Read-only reconciliation. Paid wages must have original sources, real transfers and a completed parent. */
public final class EconomyPaymentValidation {
    private EconomyPaymentValidation() {}
    private record Key(UUID segment,long period) {}
    private record Debt(CompanyId company,UUID building,RegionId region,CurrencyId currency,long amount,long paid) {}
    public static void validate(Connection c,SqliteTransactions.ReadSession snapshot)throws SQLException {
        snapshot.requireConnection(c);EconomyFinancialValidation.validate(c,snapshot);
        var time=SqliteEconomicTimeRepository.inSnapshot(c,snapshot);
        var finance=new SqliteFinanceAccountRepository(c,new Object());
        var sectors=new HashMap<String,RegionalHouseholdSector>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT h.*,b.consumption,b.savings,b.carried_period,b.revision FROM regional_household_sectors h LEFT JOIN regional_household_budgets b USING(region_id,currency_id)")){
            while(r.next()){
                require(r.getObject("revision")!=null);
                var sector=new RegionalHouseholdSector(RegionId.parse(r.getString("region_id")),CurrencyId.parse(r.getString("currency_id")),canonical(r.getString("account_id")));
                var budget=new HouseholdBudget(sector,r.getLong("consumption"),r.getLong("savings"),r.getLong("carried_period"),r.getLong("revision"));
                budget.requireCoveredBy(finance.inSnapshot(snapshot,sector.accountId()));require(budget.carriedPeriod()<=time.periodId());sectors.put(sector.region()+":"+sector.currency(),sector);
            }
        }
        var debts=new HashMap<Key,Debt>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT l.*,p.company_id,p.building_id,p.region_id,p.currency_id FROM payroll_liabilities l JOIN payroll_accruals p ON p.id=l.segment_id")){
            while(r.next())debts.put(new Key(canonical(r.getString("segment_id")),r.getLong("period_id")),new Debt(CompanyId.parse(r.getString("company_id")),canonical(r.getString("building_id")),RegionId.parse(r.getString("region_id")),CurrencyId.parse(r.getString("currency_id")),r.getLong("amount"),r.getLong("paid")));
        }
        long processed,revision;
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM payroll_payment_runtime")){
            require(r.next()&&r.getLong("id")==1);processed=r.getLong("processed_period");revision=r.getLong("revision");require(!r.next());
        }
        require(processed<=time.payrollTick()/1200&&processed==revision);
        var paid=new HashMap<Key,Long>();long previous=0;int runCount=0;
        var reader=new SqlitePayrollPaymentRepository(c,new Object());
        try(var s=c.createStatement();var cycles=s.executeQuery("SELECT * FROM payroll_payment_cycles ORDER BY period_id")){
            while(cycles.next()){
                long period=cycles.getLong("period_id");require(period==Math.incrementExact(previous));previous=period;
                require(cycles.getLong("boundary_tick")==Math.multiplyExact(period,1200L)&&period<=time.payrollTick()/1200&&period<=Math.incrementExact(processed));
                boolean completed=period<=processed;
                require(cycles.getString("status").equals(completed?"ACKNOWLEDGED":"PLANNED")&&cycles.getLong("revision")==(completed?1:0));
                var expectedCompanies=new HashSet<CompanyId>();
                for(var entry:debts.entrySet())if(entry.getKey().period()<=period&&entry.getValue().amount()>paid.getOrDefault(entry.getKey(),0L))expectedCompanies.add(entry.getValue().company());
                var actualCompanies=new HashSet<CompanyId>();
                try(var q=c.prepareStatement("SELECT * FROM payroll_payment_runs WHERE period_id=? ORDER BY company_id")){
                    q.setLong(1,period);try(var runs=q.executeQuery()){
                        while(runs.next()){
                            runCount++;var command=new PayrollPaymentRun.Command(canonical(runs.getString("id")),CompanyId.parse(runs.getString("company_id")),period);
                            require(actualCompanies.add(command.company()));var run=reader.inSnapshot(snapshot,command).orElseThrow();
                            boolean posted=run.status()==PayrollPaymentRun.Status.COMPLETED;
                            require(!completed||run.status()!=PayrollPaymentRun.Status.PENDING);require(run.revision()==(posted?1:0));
                            if(posted){require(runs.getLong("decided_tick")>=command.cutoffTick()&&runs.getLong("decided_tick")<=time.acceptedTick());verifyRun(c,snapshot,run,debts,paid,sectors,finance);}
                            long attempt=0;
                            try(var a=c.prepareStatement("SELECT * FROM payroll_payment_failures WHERE run_id=? ORDER BY attempt")){
                                a.setString(1,command.id().toString());try(var failures=a.executeQuery()){while(failures.next()){
                                    require(failures.getLong("attempt")==Math.incrementExact(attempt));attempt=failures.getLong("attempt");
                                    require(failures.getLong("observed_tick")>=command.cutoffTick()&&failures.getLong("observed_tick")<=time.acceptedTick());
                                }}
                            }
                            if(run.status()==PayrollPaymentRun.Status.BLOCKED)require(attempt>0);
                        }
                    }
                }
                require(actualCompanies.equals(expectedCompanies));
            }
        }
        require(previous==processed||previous==Math.incrementExact(processed));
        require(scalar(c,"SELECT COUNT(*) FROM payroll_payment_runs")==runCount);
        for(var entry:debts.entrySet())require(entry.getValue().paid()==paid.getOrDefault(entry.getKey(),0L));
        try(var s=c.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){require(!r.next());}
    }
    private static void verifyRun(Connection c,SqliteTransactions.ReadSession snapshot,PayrollPaymentRun run,Map<Key,Debt> debts,Map<Key,Long> paid,
                                  Map<String,RegionalHouseholdSector> sectors,SqliteFinanceAccountRepository finance)throws SQLException {
        var expected=new HashMap<Key,Long>();
        for(var entry:debts.entrySet())if(entry.getValue().company().equals(run.command().company())&&entry.getKey().period()<=run.command().periodId()){
            long unpaid=Math.subtractExact(entry.getValue().amount(),paid.getOrDefault(entry.getKey(),0L));if(unpaid>0)expected.put(entry.getKey(),unpaid);
        }
        var grouped=new HashMap<CurrencyId,List<PayrollPaymentLine>>();var actual=new HashSet<Key>();
        var sorted=new ArrayList<>(run.lines());sorted.sort(Comparator.comparing((PayrollPaymentLine l)->l.currency().toString()).thenComparing(l->l.building().toString()).thenComparing(l->l.segment().toString()).thenComparingLong(PayrollPaymentLine::liabilityPeriod));
        require(sorted.equals(run.lines()));int number=0;
        try(var q=c.prepareStatement("SELECT line,transfer_event FROM payroll_payment_lines WHERE run_id=? ORDER BY line")){
            q.setString(1,run.command().id().toString());try(var rows=q.executeQuery()){
                for(var line:run.lines()){
                    require(rows.next()&&rows.getLong("line")==number++);var key=new Key(line.segment(),line.liabilityPeriod());var debt=debts.get(key);
                    require(actual.add(key)&&debt!=null&&Objects.equals(expected.get(key),line.debtBefore()));
                    require(debt.company().equals(run.command().company())&&debt.building().equals(line.building())&&debt.region().equals(line.region())&&debt.currency().equals(line.currency()));
                    var sector=sectors.get(line.region()+":"+line.currency());require(sector!=null&&sector.accountId().equals(line.recipientAccount()));
                    String event=rows.getString("transfer_event");
                    if(line.amount()==0)require(event==null);
                    else require(event!=null&&event.equals("transfer:"+PayrollPaymentRun.transferId(run.command().id(),line.segment(),line.liabilityPeriod())));
                    grouped.computeIfAbsent(line.currency(),x->new ArrayList<>()).add(line);
                }
                require(!rows.next());
            }
        }
        require(actual.equals(expected.keySet())&&grouped.size()==run.partitions().size());var currencies=new HashSet<CurrencyId>();
        for(var part:run.partitions()){
            require(currencies.add(part.currency())&&grouped.containsKey(part.currency()));var lines=grouped.get(part.currency());long due=0,amount=0;
            var account=finance.inSnapshot(snapshot,part.sourceAccount());require(account.ownerKind()==FinanceAccount.OwnerKind.COMPANY&&account.ownerId().equals(run.command().company().value())&&account.currency().equals(part.currency()));
            for(var line:lines){due=Math.addExact(due,line.debtBefore());amount=Math.addExact(amount,line.amount());}
            require(due==part.totalDue()&&amount==part.paid()&&PayrollPaymentPlanner.allocate(lines,part.availableCash()).equals(lines));
            try(var q=c.prepareStatement("SELECT account_id,balance_after FROM finance_ledger WHERE sequence=?")){
                q.setLong(1,part.ledgerSequence());try(var r=q.executeQuery()){require(r.next()&&r.getString(1).equals(part.sourceAccount().toString())&&r.getLong(2)==part.cashBalance());}
            }
            long reserved=0;
            try(var q=c.prepareStatement("SELECT r.account_id,r.amount FROM payroll_payment_cash_locks x JOIN cash_reservations r ON r.id=x.reservation_id WHERE x.run_id=? AND x.currency_id=?")){
                q.setString(1,run.command().id().toString());q.setString(2,part.currency().toString());try(var r=q.executeQuery()){while(r.next()){require(r.getString(1).equals(part.sourceAccount().toString()));reserved=Math.addExact(reserved,r.getLong(2));}}
            }
            require(reserved==part.reservedCash());long first=Long.MAX_VALUE;
            for(var line:lines)if(line.amount()>0){
                var transfer=line.transfer(run.command().id(),part.sourceAccount());
                try(var q=c.prepareStatement("SELECT * FROM finance_events WHERE id=?")){
                    q.setString(1,"transfer:"+transfer.id());try(var r=q.executeQuery()){
                        require(r.next()&&r.getString("kind").equals("TRANSFER")&&r.getString("source_account").equals(transfer.source().toString())&&r.getString("target_account").equals(transfer.destination().toString())&&r.getString("currency_id").equals(transfer.currency().toString())&&r.getLong("amount")==transfer.amount());
                    }
                }
                try(var q=c.prepareStatement("SELECT sequence FROM finance_ledger WHERE event_id=? AND line=0")){
                    q.setString(1,"transfer:"+transfer.id());try(var r=q.executeQuery()){require(r.next());first=Math.min(first,r.getLong(1));}
                }
            }
            if(amount>0)try(var q=c.prepareStatement("SELECT MAX(sequence) FROM finance_ledger WHERE account_id=? AND sequence<?")){
                q.setString(1,part.sourceAccount().toString());q.setLong(2,first);try(var r=q.executeQuery()){require(r.next()&&r.getLong(1)==part.ledgerSequence());}
            }
        }
        for(var line:run.lines())paid.merge(new Key(line.segment(),line.liabilityPeriod()),line.amount(),Math::addExact);
    }
    private static long scalar(Connection c,String sql)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(sql)){require(r.next());return r.getLong(1);}}
    private static UUID canonical(String value){var id=UUID.fromString(value);require(id.toString().equals(value));return id;}
    private static void require(boolean valid){if(!valid)throw new IllegalStateException("Inconsistent v8 payroll payment, cash provenance or household budget; no repair attempted");}
}
