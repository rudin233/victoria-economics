package com.bbmurloc.victoriaeconomics.server.region.infrastructure;

import com.bbmurloc.victoriaeconomics.server.region.domain.*;
import com.bbmurloc.victoriaeconomics.server.region.port.RegionRepository;
import com.bbmurloc.victoriaeconomics.server.storage.sqlite.SqliteTransactions;
import java.sql.*;
import java.util.*;

public final class SqliteRegionRepository implements RegionRepository {
    private final Connection connection;
    private final Object lock;
    public SqliteRegionRepository(Connection connection, Object lock) { this.connection = connection; this.lock = lock; }
    @Override public void create(Region identity) {
        synchronized (lock) {
            SqliteTransactions.write(connection, session -> {
                try (var statement = connection.prepareStatement("INSERT INTO regions(id) VALUES(?)")) {
                    statement.setString(1, identity.id().toString());
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }
    @Override public Optional<Region> find(RegionId id) {
        synchronized (lock) {
            SqliteTransactions.requireCommittedReads(connection);
            try (var statement = connection.prepareStatement("SELECT id FROM regions WHERE id=?")) {
                statement.setString(1, id.toString());
                try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(new Region(RegionId.parse(rows.getString(1)))) : Optional.empty(); }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot read region directory", failure); }
        }
    }
}

