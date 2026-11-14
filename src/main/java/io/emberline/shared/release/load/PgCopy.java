package io.emberline.shared.release.load;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * PostgreSQL {@code COPY ... FROM STDIN} through pgjdbc's CopyManager. The driver is a runtime-only dependency
 * (build.gradle.kts), so the API is reached reflectively; the stream is pulled lazily (no full materialisation).
 */
public final class PgCopy {

    private PgCopy() {
    }

    public static long copyIn(Connection connection, String copySql, InputStream rows) throws SQLException {
        try {
            Class<?> pgConnection = Class.forName("org.postgresql.PGConnection");
            Object pg = connection.unwrap(pgConnection);
            Object copyApi = pgConnection.getMethod("getCopyAPI").invoke(pg);
            Method copyIn = copyApi.getClass().getMethod("copyIn", String.class, InputStream.class, int.class);
            return (Long) copyIn.invoke(copyApi, copySql, rows, 1 << 16);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("COPY failed", cause);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("PostgreSQL COPY API unavailable", e);
        }
    }
}
