package io.emberline.harmonized.adapter.out.parquet;

import io.emberline.harmonized.adapter.out.HarmonizedSettings;
import io.emberline.shared.sql.SqlResource;
import jakarta.annotation.PreDestroy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.duckdb.DuckDBConnection;
import org.springframework.stereotype.Component;

/**
 * Read-only in-memory DuckDB for the release parquet files (API side; ingest has its own). One base connection,
 * a duplicate per query (thread-safe); bounded memory_limit and threads from {@code emberline.harmonized.duckdb}.
 */
@Component
class ParquetReader {

    private final HarmonizedSettings.Duck settings;
    private DuckDBConnection base;

    ParquetReader(HarmonizedSettings settings) {
        this.settings = settings.duckdb();
    }

    synchronized Connection open() throws SQLException {
        if (base == null) {
            Properties props = new Properties();
            DuckDBConnection c = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:", props);
            try (Statement s = c.createStatement()) {
                s.execute("SET memory_limit = '" + settings.memoryLimit().replace("'", "") + "'");
                s.execute("SET threads = " + settings.threads());
            }
            base = c;
        }
        return base.duplicate();
    }

    /** Creates the connection-local temp table {@code cells(cell_id)} from the given ids. */
    static void cells(Connection c, long[] ids) throws SQLException {
        Long[] boxed = new Long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            boxed[i] = ids[i];
        }
        Array array = c.createArrayOf("BIGINT", boxed);
        try (PreparedStatement ps = c.prepareStatement(SqlResource.text("harmonized/duck_cells_temp.sql"))) {
            ps.setArray(1, array);
            ps.execute();
        }
    }

    @PreDestroy
    synchronized void close() throws SQLException {
        if (base != null) {
            base.close();
            base = null;
        }
    }
}
