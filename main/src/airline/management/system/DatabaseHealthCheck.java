package airline.management.system;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Run independently with: java ... airline.management.system.DatabaseHealthCheck */
public final class DatabaseHealthCheck {
    private DatabaseHealthCheck() { }
    public static void main(String[] args) {
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            if (rs.next()) {
                System.out.println("Database connection successful.");
                System.out.println("Read-only health check passed (SELECT 1).");
            }
            System.out.println(DatabaseConfig.load().description());
        } catch (Exception exception) {
            System.err.println("Database connection failed.");
            System.err.println("Exception class: " + exception.getClass().getName());
            System.err.println("Exception message: " + exception.getMessage());
            Throwable cause = exception.getCause();
            if (cause instanceof java.sql.SQLException) {
                java.sql.SQLException sqlCause = (java.sql.SQLException) cause;
                System.err.println("SQLState: " + sqlCause.getSQLState());
                System.err.println("Vendor error code: " + sqlCause.getErrorCode());
            } else if (exception instanceof java.sql.SQLException) {
                java.sql.SQLException sqlEx = (java.sql.SQLException) exception;
                if (sqlEx.getSQLState() != null) {
                    System.err.println("SQLState: " + sqlEx.getSQLState());
                    System.err.println("Vendor error code: " + sqlEx.getErrorCode());
                }
            }
            if (cause != null) {
                System.err.println("Nested cause message: " + cause.getMessage());
            }
            System.exit(1);
        }
    }
}
