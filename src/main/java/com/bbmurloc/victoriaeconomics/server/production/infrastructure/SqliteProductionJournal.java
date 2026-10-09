package com.bbmurloc.victoriaeconomics.server.production.infrastructure;

import com.bbmurloc.victoriaeconomics.server.production.port.ProductionJournal;
import java.sql.*;
import java.util.*;

public final class SqliteProductionJournal implements ProductionJournal {
    private final Connection connection;

    public SqliteProductionJournal(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void recordStart(StartIntent intent) {
        try (var statement = connection.prepareStatement("INSERT INTO production_start_intents(batch_id, building_id) VALUES (?, ?)")) {
            statement.setString(1, intent.batchId().toString());
            statement.setString(2, intent.buildingId().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record production start: " + intent.batchId(), e);
        }
    }

    @Override
    public void finishStart(UUID batch) {
        try (var statement = connection.prepareStatement("DELETE FROM production_start_intents WHERE batch_id = ?")) {
            statement.setString(1, batch.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to finish production start: " + batch, e);
        }
    }

    @Override
    public List<StartIntent> pendingStarts() {
        List<StartIntent> result = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT batch_id, building_id FROM production_start_intents"); var rows = statement.executeQuery()) {
            while (rows.next())
                result.add(new StartIntent(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2))));
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to recover start intents", e);
        }
    }

    @Override
    public boolean containsBatch(UUID batch) {
        try (var statement = connection.prepareStatement("SELECT 1 FROM production_batches WHERE batch_id = ?")) {
            statement.setString(1, batch.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to inspect committed batch", e);
        }
    }
}
