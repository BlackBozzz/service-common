package com.argent.servicecommon.report;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/** Обёртка над {@link Connection}: каждому созданному запросу выставляет таймаут выполнения и предел строк. */
final class QueryTimeoutConnection {

    private QueryTimeoutConnection() {
    }

    /**
     * Оборачивает соединение; таймаут округляется вверх до целых секунд, {@code 0} — без ограничения.
     * Драйверу сообщается {@code maxRows + 1} строк (на единицу больше лимита отчёта, чтобы лимит был виден),
     * {@code maxRows <= 0} — без ограничения.
     */
    static Connection wrap(Connection target, Duration timeout, int maxRows) {
        int seconds = timeoutSeconds(timeout);
        int rowLimit = maxRows > 0 ? (int) Math.min((long) maxRows + 1, Integer.MAX_VALUE) : 0;
        InvocationHandler handler = (proxy, method, args) -> invoke(target, seconds, rowLimit, proxy, method, args);
        return (Connection) Proxy.newProxyInstance(
                QueryTimeoutConnection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    static int timeoutSeconds(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return 0;
        }
        long seconds = timeout.getSeconds() + (timeout.getNano() > 0 ? 1 : 0);
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    private static Object invoke(Connection target, int seconds, int rowLimit, Object proxy, Method method, Object[] args)
            throws Throwable {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "QueryTimeoutConnection[" + target + "]";
            default:
                break;
        }
        Object result;
        try {
            result = method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
        if ((seconds > 0 || rowLimit > 0) && result instanceof Statement statement
                && createsStatement(method.getName())) {
            applyLimits(statement, seconds, rowLimit);
        }
        return result;
    }

    private static boolean createsStatement(String name) {
        return name.startsWith("prepareStatement") || name.startsWith("createStatement")
                || name.startsWith("prepareCall");
    }

    private static void applyLimits(Statement statement, int seconds, int rowLimit) throws SQLException {
        try {
            if (seconds > 0) {
                statement.setQueryTimeout(seconds);
            }
            if (rowLimit > 0) {
                statement.setMaxRows(rowLimit);
            }
        } catch (SQLException e) {
            statement.close();
            throw e;
        }
    }
}
