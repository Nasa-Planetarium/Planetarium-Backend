package io.emberline.shared.release.load;

import io.emberline.shared.sql.SqlResource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Release partitions: create the child ({@code <parent>_r<release_id>}, LIKE parent), COPY rows into it, build its
 * indexes after the COPY, add CHECK (release_id = id), ANALYZE, ATTACH. Parents are created by Flyway.
 */
@Component
public class Partitions {

    private static final Logger log = LoggerFactory.getLogger(Partitions.class);

    private final DataSource dataSource;

    public Partitions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static String child(String parent, long releaseId) {
        return parent + "_r" + releaseId;
    }

    private static String render(String sql, String parent, long releaseId) {
        String child = child(parent, releaseId);
        return sql.replace("{parent}", parent).replace("{child}", child)
                .replace("{child_name}", child.substring(child.indexOf('.') + 1))
                .replace("{release_id}", Long.toString(releaseId));
    }

    public void create(String parent, long releaseId) throws SQLException {
        drop(parent, releaseId);
        execute(parent, releaseId, SqlResource.statements("shared/partition_create.sql"));
    }

    /** Streams rows into the child; {@code copyTemplate} uses {@code {child}}. Logs rows and rows/s. */
    public long copy(String parent, long releaseId, String copyTemplate, InputStream rows) throws SQLException {
        long started = System.nanoTime();
        long n;
        try (Connection c = dataSource.getConnection()) {
            n = PgCopy.copyIn(c, render(copyTemplate, parent, releaseId), rows);
        }
        double secs = Math.max(1e-6, (System.nanoTime() - started) / 1e9);
        log.info("COPY {} rows={} seconds={} rows_per_s={}", child(parent, releaseId), n, String.format("%.1f", secs),
                Math.round(n / secs));
        return n;
    }

    /** Index statements use {@code {child}} and {@code {child_name}}; then CHECK, ANALYZE, ATTACH. */
    public void finish(String parent, long releaseId, List<String> indexStatements) throws SQLException {
        long started = System.nanoTime();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String setting : SqlResource.statements("shared/session_bulk.sql")) {
                s.execute(setting);
            }
            for (String index : indexStatements) {
                s.execute(render(index, parent, releaseId));
            }
        }
        execute(parent, releaseId, SqlResource.statements("shared/partition_attach.sql"));
        log.info("INDEX+ATTACH {} seconds={}", child(parent, releaseId),
                String.format("%.1f", (System.nanoTime() - started) / 1e9));
    }

    public void drop(String parent, long releaseId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            boolean attached;
            try (PreparedStatement ps = c.prepareStatement(SqlResource.text("shared/partition_is_attached.sql"))) {
                ps.setString(1, child(parent, releaseId));
                ps.setString(2, parent);
                try (ResultSet rs = ps.executeQuery()) {
                    attached = rs.next() && rs.getLong(1) > 0;
                }
            }
            try (Statement s = c.createStatement()) {
                if (attached) {
                    s.execute(render(SqlResource.text("shared/partition_detach.sql"), parent, releaseId));
                }
                s.execute(render(SqlResource.text("shared/partition_drop.sql"), parent, releaseId));
            }
        }
    }

    private void execute(String parent, long releaseId, List<String> statements) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(render(sql, parent, releaseId));
            }
        }
    }

    /** Bulk helper for small tables: copies {@code rows} lines produced by the encoder. */
    public long load(String parent, long releaseId, String copyTemplate, InputStream rows, List<String> indexes)
            throws SQLException {
        create(parent, releaseId);
        long n = copy(parent, releaseId, copyTemplate, rows);
        finish(parent, releaseId, indexes);
        return n;
    }

    public static Map<String, Long> rows(String table, long n) {
        return Map.of(table, n);
    }
}
