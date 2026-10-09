package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.workforce.employment.EmploymentRegistry;
import com.google.gson.Gson;

import java.sql.*;
import java.util.*;

public final class SqliteEmploymentRepository {
    private final Connection connection;
    private final Gson gson = new Gson();

    public SqliteEmploymentRepository(Connection connection) {
        this.connection = connection;
    }

    public void save(EmploymentRegistry.State state) {
        try (var statement = connection.prepareStatement("INSERT INTO employment_state(id, state_json) VALUES(1, ?) ON CONFLICT(id) DO UPDATE SET state_json=excluded.state_json")) {
            statement.setString(1, gson.toJson(state));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save employment", e);
        }
    }

    public EmploymentRegistry.State load() {
        try (var statement = connection.prepareStatement("SELECT state_json FROM employment_state WHERE id = 1"); var rows = statement.executeQuery()) {
            return rows.next() ? gson.fromJson(rows.getString(1), EmploymentRegistry.State.class) : new EmploymentRegistry.State(List.of(), List.of(), Set.of());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to restore employment", e);
        }
    }
}
