package com.bbmurloc.victoriaeconomics.server.financial;

import java.lang.reflect.*;
import java.sql.*;
import java.util.function.Consumer;

/** Injects around real guarded JDBC operations, never replaces durable financial facts. */
public final class JdbcFaults implements InvocationHandler {
    public final Connection connection;
    private final Connection delegate;
    public boolean beforeCommit, afterCommit, rollbackFailure, closeFailure, resetFailure, releaseFailure;
    public int commits, resets;
    public Consumer<String> beforeSql = sql -> {};
    public JdbcFaults(Connection delegate) {
        this.delegate = delegate; connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
    }
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "commit" -> { commits++; if (beforeCommit) throw new SQLException("before commit"); }
            case "rollback" -> { if (rollbackFailure) throw new SQLException("rollback failed"); }
            case "close" -> { if (closeFailure) throw new SQLException("close failed"); }
            case "releaseSavepoint" -> { if (releaseFailure) throw new SQLException("savepoint release failed"); }
            case "setAutoCommit" -> { if (Boolean.TRUE.equals(args[0])) { resets++; if (resetFailure) throw new SQLException("cleanup failed"); } }
            case "prepareStatement" -> beforeSql.accept((String) args[0]);
        }
        Object result;
        try { result = method.invoke(delegate, args); } catch (InvocationTargetException e) { throw e.getCause(); }
        if (method.getName().equals("createStatement")) {
            Statement statement = (Statement) result;
            return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                if (a != null && a.length > 0 && a[0] instanceof String sql) beforeSql.accept(sql);
                try { return m.invoke(statement,a); } catch (InvocationTargetException e) { throw e.getCause(); }
            });
        }
        if (method.getName().equals("commit") && afterCommit) throw new SQLException("durable commit receipt lost");
        return result;
    }
}
