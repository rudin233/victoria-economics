package com.bbmurloc.victoriaeconomics.server.finance.infrastructure;

import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.port.FinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.*;
import java.sql.*;
import java.util.*;

public final class SqliteFinanceAccountRepository implements FinanceAccountRepository {
    private final Connection connection;
    private final Object lock;
    public SqliteFinanceAccountRepository(Connection connection, Object lock) { this.connection = connection; this.lock = lock; }
    @Override public FinanceAccount load(UUID id) { synchronized (lock) { return SqliteTransactions.read(connection, tx -> read(id)); } }
    private FinanceAccount read(UUID id) throws SQLException {
        try (var s = connection.prepareStatement("SELECT * FROM finance_accounts WHERE id=?")) {
            s.setString(1, id.toString());
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Finance account missing: " + id);
                String company = r.getString("company_id");
                UUID owner = UUID.fromString(company == null ? r.getString("region_id") : company);
                long reserved = 0;
                try (var q = connection.prepareStatement("SELECT amount FROM cash_reservations WHERE account_id=? AND active=1")) {
                    q.setString(1, id.toString());
                    try (var rows = q.executeQuery()) { while (rows.next()) reserved = Math.addExact(reserved, rows.getLong(1)); }
                }
                return new FinanceAccount(id, company == null ? FinanceAccount.OwnerKind.REGION : FinanceAccount.OwnerKind.COMPANY,
                        owner, CurrencyId.parse(r.getString("currency_id")), r.getLong("balance"), reserved, r.getLong("revision"));
            }
        }
    }
    @Override public List<LedgerEntry> ledger(UUID id) { synchronized (lock) {
        return SqliteTransactions.read(connection, tx -> {
            read(id); var result = new ArrayList<LedgerEntry>();
            try (var s = connection.prepareStatement("SELECT * FROM finance_ledger WHERE account_id=? ORDER BY sequence")) {
                s.setString(1, id.toString());
                try (var r = s.executeQuery()) { while (r.next()) result.add(new LedgerEntry(r.getString("event_id"), r.getInt("line"), id, r.getLong("delta"), r.getLong("balance_after"))); }
            }
            return List.copyOf(result);
        });
    } }
    @Override public Optional<TransferReceipt> verify(TransferReceipt command) { synchronized (lock) {
        SqliteTransactions.requireCommittedReads(connection);
        try { return receipt(command); } catch (SQLException e) { throw new IllegalStateException("Cannot verify transfer", e); }
    } }
    private Optional<TransferReceipt> receipt(TransferReceipt command) throws SQLException {
        try (var s = connection.prepareStatement("SELECT * FROM finance_events WHERE id=?")) {
            s.setString(1, "transfer:" + command.id());
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                var stored = new TransferReceipt(command.id(), UUID.fromString(r.getString("source_account")), UUID.fromString(r.getString("target_account")), CurrencyId.parse(r.getString("currency_id")), r.getLong("amount"));
                if (!r.getString("kind").equals("TRANSFER") || !stored.equals(command)) throw new IllegalArgumentException("Transfer idempotency key conflicts with full command");
                return Optional.of(stored);
            }
        }
    }
    public Session inTransaction(SqliteTransactions.WriteSession tx) { tx.requireConnection(connection); return new Session(tx); }
    public FinanceAccount inSnapshot(SqliteTransactions.ReadSession tx,UUID id)throws SQLException {tx.requireConnection(connection);return read(id);}
    public final class Session {
        private final SqliteTransactions.WriteSession tx;
        private Session(SqliteTransactions.WriteSession tx) { this.tx = tx; }
        public FinanceAccount load(UUID id) throws SQLException { return tx.perform(() -> read(id)); }
        public Optional<FinanceAccount> find(FinanceAccount.OwnerKind kind,UUID owner,CurrencyId currency)throws SQLException {return tx.perform(()->{
            Objects.requireNonNull(kind);Objects.requireNonNull(owner);Objects.requireNonNull(currency);
            String column=kind==FinanceAccount.OwnerKind.COMPANY?"company_id":"region_id";
            try(var s=connection.prepareStatement("SELECT id FROM finance_accounts WHERE "+column+"=? AND currency_id=?")){
                s.setString(1,owner.toString());s.setString(2,currency.toString());
                try(var r=s.executeQuery()){return r.next()?Optional.of(read(UUID.fromString(r.getString(1)))):Optional.empty();}
            }
        });}
        public void registerCurrency(CurrencyId id, String name) throws SQLException { tx.perform(() -> {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Explicit currency name required");
            try (var s = connection.prepareStatement("INSERT INTO currencies(id,name) VALUES(?,?)")) { s.setString(1, id.toString()); s.setString(2, name); s.executeUpdate(); }
            return null;
        }); }
        public FinanceAccount open(UUID id, FinanceAccount.OwnerKind kind, UUID owner, CurrencyId currency) throws SQLException { return tx.perform(() -> {
            Objects.requireNonNull(kind); Objects.requireNonNull(owner); Objects.requireNonNull(currency);
            try (var s = connection.prepareStatement("SELECT id FROM finance_accounts WHERE id=?")) {
                s.setString(1, id.toString());
                try (var r = s.executeQuery()) { if (r.next()) {
                    var prior = read(id);
                    if (prior.ownerKind() != kind || !prior.ownerId().equals(owner) || !prior.currency().equals(currency)) throw new IllegalArgumentException("Account identity conflict");
                    return prior;
                } }
            }
            try (var s = connection.prepareStatement("INSERT INTO finance_accounts(id,company_id,region_id,currency_id,balance,revision) VALUES(?,?,?,?,0,0)")) {
                s.setString(1, id.toString()); s.setString(2, kind == FinanceAccount.OwnerKind.COMPANY ? owner.toString() : null);
                s.setString(3, kind == FinanceAccount.OwnerKind.REGION ? owner.toString() : null); s.setString(4, currency.toString()); s.executeUpdate();
            }
            event("open:" + id, "OPEN", null, id, currency, 0, "zero balance account opening");
            ledger("open:" + id, 0, id, 0, 0);
            return read(id);
        }); }
        public TransferReceipt transfer(TransferReceipt command) throws SQLException { return tx.perform(() -> {
            var prior = receipt(command); if (prior.isPresent()) return prior.get();
            var source = read(command.source()); var destination = read(command.destination());
            if (!source.currency().equals(command.currency()) || !destination.currency().equals(command.currency())) throw new IllegalArgumentException("Currency mismatch; no FX supported");
            if (source.available() < command.amount()) throw new IllegalStateException("Insufficient unreserved cash");
            long debit = Math.subtractExact(source.balance(), command.amount()), credit = Math.addExact(destination.balance(), command.amount());
            requireBudgetCoverage(source.id(),Math.subtractExact(debit,source.reserved()));
            update(source, debit); update(destination, credit);
            String eventId = "transfer:" + command.id();
            event(eventId, "TRANSFER", source.id(), destination.id(), command.currency(), command.amount(), "account transfer");
            ledger(eventId, 0, source.id(), Math.negateExact(command.amount()), debit);
            ledger(eventId, 1, destination.id(), command.amount(), credit);
            return command;
        }); }
        public CashReservation reserve(CashReservation command) throws SQLException { return tx.perform(() -> {
            if (!command.active()) throw new IllegalArgumentException("New reservation must be active");
            var old = reservation(command.id());
            if (old.isPresent()) {
                var r = old.get();
                if (!r.accountId().equals(command.accountId()) || r.amount() != command.amount() || !r.purpose().equals(command.purpose())) throw new IllegalArgumentException("Reservation key conflict");
                return r; // A released reservation is a permanent receipt, never reactivated by retry.
            }
            var account = read(command.accountId());
            if (account.available() < command.amount()) throw new IllegalStateException("Insufficient unreserved cash");
            requireBudgetCoverage(account.id(),Math.subtractExact(account.available(),command.amount()));
            update(account, account.balance());
            try (var s = connection.prepareStatement("INSERT INTO cash_reservations VALUES(?,?,?,?,1)")) {
                s.setString(1, command.id().toString()); s.setString(2, command.accountId().toString()); s.setLong(3, command.amount()); s.setString(4, command.purpose()); s.executeUpdate();
            }
            return command;
        }); }
        public CashReservation release(UUID id, UUID expectedAccount) throws SQLException { return tx.perform(() -> {
            var old = reservation(id).orElseThrow(() -> new IllegalStateException("Cash reservation missing"));
            if (!old.accountId().equals(expectedAccount)) throw new IllegalArgumentException("Reservation account mismatch");
            if (!old.active()) return old;
            var account = read(expectedAccount); update(account, account.balance());
            try (var s = connection.prepareStatement("UPDATE cash_reservations SET active=0 WHERE id=? AND active=1")) {
                s.setString(1, id.toString()); if (s.executeUpdate() != 1) throw new IllegalStateException("Reservation conflict");
            }
            return new CashReservation(id, old.accountId(), old.amount(), old.purpose(), false);
        }); }
        private void update(FinanceAccount prior, long balance) throws SQLException {
            try (var s = connection.prepareStatement("UPDATE finance_accounts SET balance=?,revision=? WHERE id=? AND revision=?")) {
                s.setLong(1, balance); s.setLong(2, Math.incrementExact(prior.revision())); s.setString(3, prior.id().toString()); s.setLong(4, prior.revision());
                if (s.executeUpdate() != 1) throw new IllegalStateException("Account CAS conflict");
            }
        }
    }
    /** Purpose allowances cannot be seized by a generic transfer/reservation. Explicitly free them first. */
    private void requireBudgetCoverage(UUID account,long available)throws SQLException {
        try(var s=connection.prepareStatement("SELECT b.consumption,b.savings FROM regional_household_budgets b JOIN regional_household_sectors h USING(region_id,currency_id) WHERE h.account_id=?")){
            s.setString(1,account.toString());try(var r=s.executeQuery()){
                if(r.next()&&Math.addExact(r.getLong(1),r.getLong(2))>available)throw new IllegalStateException("Explicit household budget adjustment required before locking or transferring allocated cash");
            }
        }
    }
    private Optional<CashReservation> reservation(UUID id) throws SQLException {
        try (var s = connection.prepareStatement("SELECT * FROM cash_reservations WHERE id=?")) {
            s.setString(1, id.toString());
            try (var r = s.executeQuery()) { return r.next() ? Optional.of(new CashReservation(id, UUID.fromString(r.getString("account_id")), r.getLong("amount"), r.getString("purpose"), r.getInt("active") == 1)) : Optional.empty(); }
        }
    }
    private void event(String id, String kind, UUID source, UUID target, CurrencyId currency, long amount, String provenance) throws SQLException {
        try (var s = connection.prepareStatement("INSERT INTO finance_events VALUES(?,?,?,?,?,?,?)")) {
            s.setString(1, id); s.setString(2, kind); s.setString(3, source == null ? null : source.toString()); s.setString(4, target.toString());
            s.setString(5, currency.toString()); s.setLong(6, amount); s.setString(7, provenance); s.executeUpdate();
        }
    }
    private void ledger(String event, int line, UUID account, long delta, long balance) throws SQLException {
        try (var s = connection.prepareStatement("INSERT INTO finance_ledger(event_id,line,account_id,delta,balance_after) VALUES(?,?,?,?,?)")) {
            s.setString(1, event); s.setInt(2, line); s.setString(3, account.toString()); s.setLong(4, delta); s.setLong(5, balance); s.executeUpdate();
        }
    }
}
