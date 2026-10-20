package com.bbmurloc.victoriaeconomics.server.finance.application;

import com.bbmurloc.victoriaeconomics.server.finance.domain.*;
import com.bbmurloc.victoriaeconomics.server.finance.infrastructure.SqliteFinanceAccountRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import java.sql.Connection;
import java.util.UUID;

/** No issuance, debug credit, payroll payment or currency conversion entry point. */
public final class FinanceService {
    private final Connection connection;
    private final Object lock;
    private final SqliteFinanceAccountRepository repository;
    public FinanceService(Connection connection, Object lock) {
        this.connection = connection; this.lock = lock; this.repository = new SqliteFinanceAccountRepository(connection, lock);
    }
    public void registerCurrency(CurrencyId id, String explicitName) { synchronized (lock) {
        SqliteTransactions.write(connection, tx -> { repository.inTransaction(tx).registerCurrency(id, explicitName); return null; });
    } }
    public FinanceAccount open(UUID id, FinanceAccount.OwnerKind kind, UUID owner, CurrencyId currency) { synchronized (lock) {
        return SqliteTransactions.write(connection, tx -> repository.inTransaction(tx).open(id, kind, owner, currency));
    } }
    public TransferReceipt transfer(TransferReceipt command) { synchronized (lock) {
        return SqliteTransactions.write(connection, tx -> repository.inTransaction(tx).transfer(command));
    } }
    public CashReservation reserve(CashReservation command) { synchronized (lock) {
        return SqliteTransactions.write(connection, tx -> repository.inTransaction(tx).reserve(command));
    } }
    public CashReservation release(UUID id, UUID account) { synchronized (lock) {
        return SqliteTransactions.write(connection, tx -> repository.inTransaction(tx).release(id, account));
    } }
}
