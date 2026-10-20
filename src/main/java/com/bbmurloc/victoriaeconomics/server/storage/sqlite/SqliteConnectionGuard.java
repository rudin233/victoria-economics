package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Sticky connection quarantine, including when the driver cannot close the physical connection. */
public final class SqliteConnectionGuard {
    private SqliteConnectionGuard() {}

    private static final class State {
        volatile Throwable failure;
    }

    private record Tracked(WeakReference<Connection> connection, State state) {}
    // Weak identity keys: JDBC wrappers need not implement equals/hashCode as connection identity.
    private static final List<Tracked> STATES = new ArrayList<>();

    /** All JDBC handles returned by this connection share its quarantine state. */
    public static Connection protect(Connection connection) {
        State state = stateOf(connection);
        requireTrusted(state);
        var guard = new Guard(connection, state, null);
        var protectedConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, guard);
        guard.owner = protectedConnection;
        synchronized (STATES) {
            STATES.add(new Tracked(new WeakReference<>(protectedConnection), state));
        }
        return protectedConnection;
    }

    static void requireTrusted(Connection connection) {
        requireTrusted(stateOf(connection));
    }

    private static void requireTrusted(State state) {
        if (state.failure != null)
            throw new CommitOutcomeUnknownException("SQLite connection is quarantined; use a fresh connection to verify durable state", state.failure);
    }

    static boolean isQuarantined(Connection connection) {
        return stateOf(connection).failure != null;
    }

    static void quarantine(Connection connection, Throwable failure) {
        State state = stateOf(connection);
        // Mark before close: a failed close must never make this connection usable again.
        synchronized (state) {
            if (state.failure == null) state.failure = failure;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException | Error close) {
            if (failure != close) failure.addSuppressed(close);
        }
    }

    private static State stateOf(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        synchronized (STATES) {
            State known = find(connection);
            if (known != null) return known;
        }
        Connection unwrapped = connection;
        try {
            // Share poison with JDBC fault/delegating wrappers around an already tracked connection.
            Connection candidate = connection.unwrap(Connection.class);
            if (candidate != null) unwrapped = candidate;
        } catch (SQLException | RuntimeException ignored) {
            // Unwrapping is optional; this exact handle is still tracked and closed on failure.
        }
        synchronized (STATES) {
            State state = find(connection);
            if (state != null) return state;
            state = find(unwrapped);
            if (state == null) state = new State();
            STATES.add(new Tracked(new WeakReference<>(connection), state));
            if (unwrapped != connection) STATES.add(new Tracked(new WeakReference<>(unwrapped), state));
            return state;
        }
    }

    // Called only under STATES' monitor; do not invoke user/driver equals or hashCode.
    private static State find(Connection connection) {
        State found = null;
        for (var entries = STATES.iterator(); entries.hasNext();) {
            var entry = entries.next();
            Connection candidate = entry.connection().get();
            if (candidate == null) entries.remove();
            else if (candidate == connection) found = entry.state();
        }
        return found;
    }

    private static final class Guard implements InvocationHandler {
        private final Object delegate;
        private final State state;
        private Connection owner;

        private Guard(Object delegate, State state, Connection owner) {
            this.delegate = delegate;
            this.state = state;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Guarded SQLite " + proxy.getClass().getInterfaces()[0].getSimpleName();
                    default -> throw new UnsupportedOperationException(name);
                };
            }
            // Closing remains possible after quarantine. isClosed means unusable, not confirmed rollback.
            if (name.equals("isClosed") && state.failure != null) return true;
            if (!name.equals("close") && state.failure != null)
                throw new SQLException("SQLite connection is quarantined", state.failure);
            if (name.equals("unwrap")) {
                Class<?> requested = (Class<?>) arguments[0];
                if (requested.isInstance(proxy)) return proxy;
                throw new SQLFeatureNotSupportedException("Cannot unwrap a guarded SQLite handle to an unguarded implementation");
            }
            if (name.equals("isWrapperFor")) return ((Class<?>) arguments[0]).isInstance(proxy);

            Object result;
            try {
                result = method.invoke(delegate, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            if (result instanceof Connection) return owner;
            Class<?> contract = result instanceof CallableStatement ? CallableStatement.class
                    : result instanceof PreparedStatement ? PreparedStatement.class
                    : result instanceof Statement ? Statement.class
                    : result instanceof ResultSet ? ResultSet.class
                    : result instanceof DatabaseMetaData ? DatabaseMetaData.class : null;
            if (contract == null) return result;
            return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                    new Guard(result, state, owner));
        }
    }
}
