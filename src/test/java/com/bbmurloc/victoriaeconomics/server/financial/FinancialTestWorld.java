package com.bbmurloc.victoriaeconomics.server.financial;

import com.bbmurloc.victoriaeconomics.server.TestIdentities;
import com.bbmurloc.victoriaeconomics.server.workforce.LaborTestWorld;
import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.application.FinanceService;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.payroll.domain.*;
import com.bbmurloc.victoriaeconomics.server.payroll.application.PayrollAccrualService;
import com.bbmurloc.victoriaeconomics.server.payroll.infrastructure.*;
import com.bbmurloc.victoriaeconomics.server.time.infrastructure.SqliteEconomicTimeRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Explicit isolated funding and staffing authority. None of these fixture methods are in the server artifact. */
public final class FinancialTestWorld implements AutoCloseable {
    public static final CurrencyId CURRENCY = new CurrencyId(new UUID(700,1));
    public final LaborTestWorld world;
    public final Connection c;
    public final FinanceService finance;
    public final SqliteFinanceAccountRepository accounts;
    public final SqlitePayrollRepository payroll;
    public final PayrollAccrualService accrual;
    public final SqliteBuildingPayrollQuery query;
    public final SqliteEconomicTimeRepository time;
    public FinancialTestWorld(Path path) throws Exception { this(path, UnaryOperator.identity()); }
    public FinancialTestWorld(Path path, UnaryOperator<Connection> decorate) throws Exception {
        world = new LaborTestWorld(path, decorate); c = world.connection;
        finance = new FinanceService(c, world.lock); accounts = new SqliteFinanceAccountRepository(c, world.lock);
        payroll = new SqlitePayrollRepository(c, world.lock); accrual = new PayrollAccrualService(c, world.lock);
        query = new SqliteBuildingPayrollQuery(c, world.lock); time = new SqliteEconomicTimeRepository(c, world.lock);
        if (scalar("SELECT COUNT(*) FROM currencies") == 0) finance.registerCurrency(CURRENCY, "Isolated test currency");
    }
    public UUID account(FinanceAccount.OwnerKind kind, UUID owner) {
        UUID id = UUID.randomUUID(); finance.open(id, kind, owner, CURRENCY); return id;
    }
    public UUID companyAccount() { return account(FinanceAccount.OwnerKind.COMPANY, TestIdentities.COMPANY.value()); }
    public UUID regionAccount() { return account(FinanceAccount.OwnerKind.REGION, TestIdentities.REGION.value()); }
    public void fundingFixture(UUID account, long amount) {
        SqliteTransactions.write(c, tx -> {
            String event = "isolated-test-issuance:" + UUID.randomUUID(); long next = Math.addExact(accounts.inTransaction(tx).load(account).balance(), amount);
            try (var s = c.prepareStatement("INSERT INTO finance_events VALUES(?,'EXTERNAL_CREDIT',NULL,?,?,?,'JUnit isolated issuance with explicit provenance; no server issuance API')")) {
                s.setString(1, event); s.setString(2, account.toString()); s.setString(3, CURRENCY.toString()); s.setLong(4, amount); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO finance_ledger(event_id,line,account_id,delta,balance_after) VALUES(?,0,?,?,?)")) {
                s.setString(1, event); s.setString(2, account.toString()); s.setLong(3, amount); s.setLong(4, next); s.executeUpdate();
            }
            try (var s = c.prepareStatement("UPDATE finance_accounts SET balance=?,revision=revision+1 WHERE id=?")) { s.setLong(1, next); s.setString(2, account.toString()); s.executeUpdate(); }
            return null;
        });
    }
    public UUID staffingFixture(int employees, long wage) throws Exception {
        sql("UPDATE payroll_runtime SET authority='BUILDING_STAFFING'");
        if(scalar("SELECT COUNT(*) FROM payroll_accruals")==0)sql("UPDATE regional_wage_rates SET amount="+wage);
        var b = world.create(); world.setSupply(100); world.commands.requestTarget(b.getId(), "laborer", employees); world.applyPendingForPersistenceTest(b.getId());
        if (scalar("SELECT COUNT(*) FROM regional_wage_rates") == 0) accrual.configureRate(new RegionalWageRate(TestIdentities.REGION, CURRENCY, time.load().acceptedTick(), wage, 0));
        return b.getId();
    }
    public UUID enroll(UUID building) {return SqliteTransactions.write(c,tx->{
        try(var s=c.prepareStatement("SELECT id FROM payroll_accruals WHERE building_id=? AND closed_tick IS NULL")){
            s.setString(1,building.toString());try(var r=s.executeQuery()){if(r.next())return UUID.fromString(r.getString(1));}
        }
        return payroll.inTransaction(tx).enroll(UUID.randomUUID(),building,CURRENCY).id();
    });}
    /** Explicit persisted time fixture for accrual interval tests, with no production/offline claim. Real callback time has its own 1200-tick test. */
    public void acceptedTimeFixture(long target) {
        SqliteTransactions.write(c, tx -> {
            if (target < timeRead(tx)) throw new IllegalArgumentException("Test time cannot go backwards");
            try (var s = c.prepareStatement("UPDATE economic_time SET accepted_tick=?,production_callback_returned_tick=?,revision=revision+1 WHERE id=1")) {
                s.setLong(1, target); s.setLong(2, target); s.executeUpdate();
            }
            try(var s=c.prepareStatement("UPDATE production_executions SET last_completed_tick=?,processing_tick=NULL")){s.setLong(1,target);s.executeUpdate();}
            return null;
        });
    }
    private long timeRead(SqliteTransactions.WriteSession tx) { return SqliteEconomicTimeRepository.inTransaction(c,tx).acceptedTick(); }
    public void changeEmployeesFixture(UUID segment, int count) {
        SqliteTransactions.write(c, tx -> {
            var p = payroll.inTransaction(tx); var old = p.load(segment); p.accrue(old, timeRead(tx));
            var repo = world.staffing.inTransaction(tx); var staffing = repo.load(old.buildingId());
            var changed = new com.bbmurloc.victoriaeconomics.server.workforce.domain.BuildingStaffing(staffing.buildingId(), count == 0 ? Map.of() : Map.of("laborer", count), staffing.pending(), Math.incrementExact(staffing.revision()));
            repo.save(changed, staffing.revision()); p.changeEmployees(segment, count, timeRead(tx)); return null;
        });
    }
    public void sql(String sql) throws SQLException { world.sql(sql); }
    public long scalar(String sql) throws SQLException { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }
    @Override public void close() throws Exception { world.close(); }
}
