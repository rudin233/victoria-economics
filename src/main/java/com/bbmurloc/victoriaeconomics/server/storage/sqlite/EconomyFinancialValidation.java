package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.finance.domain.CurrencyId;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.SqlitePayrollRepository;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import java.sql.*;
import java.util.*;

/** Read-only preflight of cross-row invariants that CHECK/FK cannot express. No repair or defaults. */
final class EconomyFinancialValidation {
    private EconomyFinancialValidation() {}
    static void validate(Connection c, SqliteTransactions.ReadSession snapshot) throws SQLException {
        var time = SqliteEconomicTimeRepository.inSnapshot(c, snapshot);
        require(scalar(c, "SELECT COUNT(*) FROM economic_time") == 1 && scalar(c, "SELECT COUNT(*) FROM payroll_runtime") == 1);
        var balances = new HashMap<String, Long>(); var currencies = new HashMap<String, String>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM currencies")) { while (r.next()) CurrencyId.parse(r.getString(1)); }
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM finance_accounts")) { while (r.next()) {
            canonical(r.getString("id")); balances.put(r.getString("id"), r.getLong("balance")); currencies.put(r.getString("id"), r.getString("currency_id"));
        } }
        var running = new HashMap<String, Long>(); var openingCounts = new HashMap<String, Integer>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM finance_ledger ORDER BY sequence")) { while (r.next()) {
            String account = r.getString("account_id");
            long next = Math.addExact(running.getOrDefault(account, 0L), r.getLong("delta"));
            require(next >= 0 && next == r.getLong("balance_after")); running.put(account, next);
        } }
        for (var account : balances.keySet()) require(Objects.equals(running.get(account), balances.get(account)));
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM finance_events")) { while (r.next()) {
            String id = r.getString("id"), kind = r.getString("kind"), source = r.getString("source_account"), target = r.getString("target_account"), currency = r.getString("currency_id");
            long amount = r.getLong("amount"); require(currency.equals(currencies.get(target)));
            if (kind.equals("TRANSFER")) { require(id.startsWith("transfer:") && currency.equals(currencies.get(source))); canonical(id.substring("transfer:".length())); }
            if (kind.equals("OPEN")) { require(id.equals("open:" + target)); openingCounts.merge(target, 1, Math::addExact); }
            int count = 0;
            try (var q = c.prepareStatement("SELECT * FROM finance_ledger WHERE event_id=? ORDER BY line")) {
                q.setString(1, id); try (var lines = q.executeQuery()) { while (lines.next()) {
                    int line = lines.getInt("line"); require(line == count++);
                    boolean debit = kind.equals("TRANSFER") && line == 0;
                    require(lines.getString("account_id").equals(debit ? source : target));
                    require(lines.getLong("delta") == (debit ? Math.negateExact(amount) : amount));
                } }
            }
            require(count == (kind.equals("TRANSFER") ? 2 : 1));
        } }
        for (String account : balances.keySet()) require(openingCounts.getOrDefault(account, 0) == 1);
        var reserved = new HashMap<String, Long>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM cash_reservations")) { while (r.next()) {
            canonical(r.getString("id")); if (r.getInt("active") == 1) reserved.merge(r.getString("account_id"), r.getLong("amount"), Math::addExact);
        } }
        for (var entry : reserved.entrySet()) require(entry.getValue() <= balances.get(entry.getKey()));
        var wageRevisions = new HashMap<String, Long>(); var wageCurrencies = new HashMap<String, String>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM regional_wage_rates ORDER BY region_id,effective_tick")) { while (r.next()) {
            String region = r.getString("region_id"), currency = r.getString("currency_id");
            require(r.getLong("revision") == Math.incrementExact(wageRevisions.getOrDefault(region, -1L)));
            wageRevisions.put(region, r.getLong("revision"));
            String prior = wageCurrencies.put(region, currency); require(prior == null || prior.equals(currency));
        } }
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM payroll_accruals")) { while (r.next()) {
            var p = SqlitePayrollRepository.decode(r); canonical(r.getString("id")); canonical(r.getString("building_id"));
            require(p.lastAccruedTick() <= time.acceptedTick() && p.lastAccruedTick() >= time.payrollTick());
            verifyAccrualHistory(c, p);
            // Task B cannot create paid wages; a later payment contract must introduce verifiable payment linkage.
            require(p.paid() == 0);
            long matured = 0, paid = 0; long lastPeriod = p.startTick() / 1200;
            try (var q = c.prepareStatement("SELECT * FROM payroll_liabilities WHERE segment_id=? ORDER BY period_id")) {
                q.setString(1, p.id().toString()); try (var lines = q.executeQuery()) { while (lines.next()) {
                    long period = lines.getLong("period_id"); require(period == Math.incrementExact(lastPeriod)); lastPeriod = period;
                    require(period <= p.lastAccruedTick() / 1200);
                    matured = Math.addExact(matured, lines.getLong("amount")); paid = Math.addExact(paid, lines.getLong("paid"));
                } }
            }
            require(lastPeriod == p.lastAccruedTick() / 1200 && matured == p.matured() && paid == p.paid());
            try (var q = c.prepareStatement("SELECT 1 FROM regional_wage_rates WHERE region_id=? AND currency_id=? AND effective_tick<=?")) {
                q.setString(1, p.region().toString()); q.setString(2, p.currency().toString()); q.setLong(3, p.startTick());
                try (var rates = q.executeQuery()) { require(rates.next()); }
            }
            if (p.closedTick() == null) {
                long employees = 0;
                try (var q = c.prepareStatement("SELECT employed FROM building_staffing_roles WHERE building_id=?")) {
                    q.setString(1, p.buildingId().toString()); try (var workers = q.executeQuery()) { while (workers.next()) employees = Math.addExact(employees, workers.getLong(1)); }
                }
                require(employees == p.employees());
                try (var q = c.prepareStatement("SELECT owner_company_id,region_id FROM economic_buildings WHERE id=?")) {
                    q.setString(1, p.buildingId().toString()); try (var b = q.executeQuery()) { require(b.next() && p.company().toString().equals(b.getString(1)) && p.region().toString().equals(b.getString(2))); }
                }
            }
        } }
        require(scalar(c, "SELECT COUNT(*) FROM payroll_accruals") == 0 || scalar(c, "SELECT COUNT(*) FROM payroll_runtime WHERE authority='BUILDING_STAFFING'") == 1);
    }
    private static long scalar(Connection c, String sql) throws SQLException { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { require(r.next()); return r.getLong(1); } }
    private static void verifyAccrualHistory(Connection c, PayrollAccrual p) throws SQLException {
        var rates = new ArrayList<RegionalWageRate>();
        try (var q = c.prepareStatement("SELECT * FROM regional_wage_rates WHERE region_id=? AND currency_id=? ORDER BY effective_tick")) {
            q.setString(1, p.region().toString()); q.setString(2, p.currency().toString());
            try (var r = q.executeQuery()) { while (r.next()) rates.add(new RegionalWageRate(p.region(), p.currency(), r.getLong("effective_tick"), r.getLong("amount"), r.getLong("revision"))); }
        }
        PayrollAccrual calculated = null; long priorRevision = -1;
        var expectedLiabilities = new ArrayList<PayrollLiability>();
        try (var q = c.prepareStatement("SELECT * FROM payroll_employment_history WHERE segment_id=? ORDER BY revision")) {
            q.setString(1, p.id().toString());
            try (var r = q.executeQuery()) { while (r.next()) {
                long tick = r.getLong("effective_tick"), count = r.getLong("employees"), revision = r.getLong("revision");
                require(revision > priorRevision && revision <= p.revision() && tick <= p.lastAccruedTick()); priorRevision = revision;
                if (calculated == null) {
                    require(tick == p.startTick() && revision == 0);
                    calculated = PayrollAccrual.start(p.id(),p.buildingId(),p.company(),p.region(),p.currency(),tick,count);
                } else {
                    require(p.closedTick() == null || tick <= p.closedTick());
                    var result = calculated.accrueThrough(tick,rates); expectedLiabilities.addAll(result.liabilities());
                    calculated = result.accrual().withEmployeesAtCursor(count,tick);
                }
            } }
        }
        require(calculated != null && calculated.employees() == p.employees());
        if (p.closedTick() != null) {
            var result = calculated.accrueThrough(p.closedTick(),rates); expectedLiabilities.addAll(result.liabilities()); calculated = result.accrual().closeAtCursor(p.closedTick());
        }
        var result = calculated.accrueThrough(p.lastAccruedTick(),rates); expectedLiabilities.addAll(result.liabilities()); calculated = result.accrual();
        require(calculated.accrued() == p.accrued() && calculated.unmatured() == p.unmatured() && calculated.matured() == p.matured() && calculated.remainder() == p.remainder());
        try (var q = c.prepareStatement("SELECT period_id,amount FROM payroll_liabilities WHERE segment_id=? ORDER BY period_id")) {
            q.setString(1,p.id().toString()); int i = 0;
            try (var r = q.executeQuery()) { while (r.next()) {
                require(i < expectedLiabilities.size()); var expected = expectedLiabilities.get(i++);
                require(r.getLong(1) == expected.periodId() && r.getLong(2) == expected.amount());
            } }
            require(i == expectedLiabilities.size());
        }
    }
    private static void canonical(String value) { require(UUID.fromString(value).toString().equals(value)); }
    private static void require(boolean valid) { if (!valid) throw new IllegalStateException("Inconsistent v7 economic time, finance ledger or payroll contract; no repair attempted"); }
}
