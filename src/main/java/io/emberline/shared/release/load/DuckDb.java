package io.emberline.shared.release.load;

import io.emberline.shared.config.EmberlineProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * In-memory DuckDB for reading release parquet files at ingest: streaming results (no full materialisation),
 * bounded memory_limit and threads, spill directory under java.io.tmpdir. Never opened by the API pods.
 */
@Component
public class DuckDb {

    private final EmberlineProperties.Ingest settings;

    public DuckDb(EmberlineProperties properties) {
        this.settings = properties.ingest();
    }

    public Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("jdbc_stream_results", "true");
        Connection c = DriverManager.getConnection("jdbc:duckdb:", props);
        try (Statement s = c.createStatement()) {
            Path spill = Path.of(System.getProperty("java.io.tmpdir"), "emberline-duckdb-" + ProcessHandle.current().pid());
            Files.createDirectories(spill);
            s.execute("SET memory_limit = '" + settings.duckdbMemoryLimit().replace("'", "") + "'");
            s.execute("SET threads = " + settings.duckdbThreads());
            s.execute("SET temp_directory = '" + spill.toString().replace("'", "''") + "'");
            s.execute("SET preserve_insertion_order = false");
        } catch (java.io.IOException e) {
            c.close();
            throw new SQLException("cannot prepare DuckDB spill directory", e);
        }
        return c;
    }

    public int copyWorkers() {
        return settings.copyWorkers();
    }
}
