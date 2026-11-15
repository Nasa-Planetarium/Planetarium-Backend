package io.emberline.acceptance.skeleton;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.EmberlineApplication;
import io.emberline.support.EmbeddedPg;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** (CORS '*' exits non-zero) and – (flyway profile) through the real main() in a child JVM. */
class SkeletonProcessTest {

    static int runMain(String... args) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(javaBin, "-Xmx512m", "-cp",
                System.getProperty("java.class.path"), EmberlineApplication.class.getName()));
        cmd.addAll(java.util.List.of(args));
        File log = Files.createTempFile("emberline-main", ".log").toFile();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("process finished").isTrue();
        return p.exitValue();
    }

    @Test
    void b0_10_wildcardCorsOriginStopsStartupWithNonZeroExit() throws Exception {
        String url = EmbeddedPg.newDatabase();
        int exit = runMain("--spring.datasource.url=" + url, "--spring.datasource.username=postgres",
                "--server.port=0", "--management.server.port=0", "--emberline.cors.allowed-origins=*");
        assertThat(exit).isNotZero();
    }

    @Test
    void b0_11_b0_12_flywayProfileCreatesHistoryPerSchemaAndIsIdempotent() throws Exception {
        String url = EmbeddedPg.newDatabase();
        String[] args = {"--spring.profiles.active=flyway", "--spring.datasource.url=" + url, "--spring.datasource.username=postgres"};
        assertThat(runMain(args)).isZero();
        DataSource ds = EmbeddedPg.dataSource(url);
        Map<String, Integer> first = historyRows(ds);
        assertThat(first.keySet()).isEqualTo(new TreeSet<>(Set.of("catalog", "harmonized", "observation", "provenance", "outlook", "ingest", "platform", "fireinfo")));
        assertThat(runMain(args)).isZero();
        assertThat(historyRows(ds)).isEqualTo(first);
    }

    static Map<String, Integer> historyRows(DataSource ds) throws Exception {
        Map<String, Integer> out = new TreeMap<>();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT table_schema FROM information_schema.tables WHERE table_name = 'flyway_schema_history'")) {
            while (r.next()) {
                out.put(r.getString(1), 0);
            }
        }
        for (String schema : out.keySet()) {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT count(*) FROM " + schema + ".flyway_schema_history")) {
                r.next();
                out.put(schema, r.getInt(1));
            }
        }
        return out;
    }
}
