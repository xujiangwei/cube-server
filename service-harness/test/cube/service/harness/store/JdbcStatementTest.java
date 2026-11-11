package cube.service.harness.store;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Driver failures during binding must close the statement and retain the original cause. */
public final class JdbcStatementTest {
    private static final class Driver {
        final Throwable bindingFailure;
        final SQLException closeFailure;
        boolean closed, executed;
        Driver(Throwable bindingFailure, SQLException closeFailure) {
            this.bindingFailure = bindingFailure; this.closeFailure = closeFailure;
        }
        Connection connection() {
            PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                    JdbcStatementTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if (method.getName().equals("setObject")) { if (bindingFailure != null) throw bindingFailure; return null; }
                        if (method.getName().equals("close")) { closed = true; if (closeFailure != null) throw closeFailure; return null; }
                        if (method.getName().equals("executeUpdate")) { executed = true; return 7; }
                        throw new AssertionError("Unexpected statement call: " + method.getName());
                    });
            return (Connection) Proxy.newProxyInstance(JdbcStatementTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) return statement;
                        throw new AssertionError("Unexpected connection call: " + method.getName());
                    });
        }
    }
    private static void failedBinding(Throwable error, boolean closeAlsoFails) throws Exception {
        SQLException closing = closeAlsoFails ? new SQLException("close failed") : null;
        Driver driver = new Driver(error, closing);
        try {
            JdbcRunStore.update(driver.connection(), "UPDATE fixture SET value=?", "value");
            throw new AssertionError("Binding failure was swallowed");
        } catch (SQLException | RuntimeException | Error actual) {
            if (actual != error) throw new AssertionError("Original driver failure was replaced", actual);
        }
        if (!driver.closed || driver.executed) throw new AssertionError("Failed binding must close without executing");
        if (closing != null && (error.getSuppressed().length != 1 || error.getSuppressed()[0] != closing))
            throw new AssertionError("Close failure must remain as a suppressed exception");
    }
    public static void main(String[] args) throws Exception {
        failedBinding(new SQLException("SQL binding failed"), false);
        failedBinding(new IllegalArgumentException("Driver rejected value"), false);
        failedBinding(new SQLException("SQL binding failed"), true);
        failedBinding(new AssertionError("Driver failed"), false);
        Driver driver = new Driver(null, null);
        if (JdbcRunStore.update(driver.connection(), "UPDATE fixture SET value=?", "value") != 7 || !driver.executed || !driver.closed)
            throw new AssertionError("Successful statement must still execute once and close");
        System.out.println("PASS JDBC statement lifecycle: binding SQL/runtime/error failures, suppressed close failure and success.");
    }
}
