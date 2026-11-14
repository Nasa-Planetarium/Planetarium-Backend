package io.emberline.shared.release.load;

import java.sql.SQLException;

/** Turns a database error during load into a reason code (23505 unique violation = duplicate key). */
public final class LoadFailures {

    private LoadFailures() {
    }

    public static String reason(SQLException e, String context) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return "DUPLICATE_KEY:" + context;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return "CONSTRAINT_VIOLATION:" + context;
            }
        }
        return "LOAD_FAILED:" + context;
    }
}
