package com.bbmurloc.victoriaeconomics.server.company.infrastructure;

import com.bbmurloc.victoriaeconomics.server.company.domain.*;
import com.bbmurloc.victoriaeconomics.server.company.port.CompanyRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import java.sql.*;
import java.util.*;

public final class SqliteCompanyRepository implements CompanyRepository {
    private final Connection connection;
    private final Object lock;
    public SqliteCompanyRepository(Connection connection, Object lock) { this.connection = connection; this.lock = lock; }
    @Override public void create(Company identity) {
        synchronized (lock) {
            SqliteTransactions.write(connection, session -> {
                try (var statement = connection.prepareStatement("INSERT INTO companies(id) VALUES(?)")) {
                    statement.setString(1, identity.id().toString());
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }
    @Override public Optional<Company> find(CompanyId id) {
        synchronized (lock) {
            SqliteTransactions.requireCommittedReads(connection);
            try (var statement = connection.prepareStatement("SELECT id FROM companies WHERE id=?")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(new Company(CompanyId.parse(rows.getString(1)))) : Optional.empty(); }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot read company directory", failure); }
        }
    }
}

