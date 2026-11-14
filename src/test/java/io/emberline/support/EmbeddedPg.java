package io.emberline.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** One embedded PostgreSQL 18 per test JVM (no Docker in the pod); each caller gets its own fresh database. */
public final class EmbeddedPg {

    private static EmbeddedPostgres pg;
    private static final AtomicInteger SEQ = new AtomicInteger();

    private EmbeddedPg() {
    }

    public static synchronized EmbeddedPostgres instance() {
        if (pg == null) {
            try {
                pg = start();
            } catch (IllegalStateException e) {
                // uid without a passwd entry (some pods): wrap initdb with the getpwuid shim and retry once
                try {
                    PasswdShim.install(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "embedded-pg"));
                    pg = start();
                } catch (IOException io) {
                    throw new UncheckedIOException(io);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // shutting down
                }
            }));
        }
        return pg;
    }

    private static EmbeddedPostgres start() throws IOException {
        return EmbeddedPostgres.builder()
                .setServerConfig("max_connections", "300")
                .setServerConfig("fsync", "off")
                .setServerConfig("synchronous_commit", "off")
                .setServerConfig("full_page_writes", "off")
                .start();
    }

    /** Creates a new empty database and returns its JDBC URL. */
    public static String newDatabase() {
        String name = "t" + ProcessHandle.current().pid() + "_" + SEQ.incrementAndGet();
        try (Connection c = instance().getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return instance().getJdbcUrl("postgres", name);
    }

    public static DataSource dataSource(String url) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser("postgres");
        return ds;
    }
}
