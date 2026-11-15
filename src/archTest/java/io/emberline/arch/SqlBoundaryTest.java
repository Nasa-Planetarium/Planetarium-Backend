package io.emberline.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** B0.18 — SQL lives in src/main/resources/sql/&lt;ctx&gt;/ and never names another context's schema; Java SQL literals only in adapter.out. */
class SqlBoundaryTest {

    static final List<String> SCHEMAS = List.of("catalog", "harmonized", "observation", "provenance", "outlook", "ingest", "platform",
            "identity", "reporting", "audit", "operations", "fireinfo");
    static final Pattern DML_LITERAL = Pattern.compile("(?i)\"(\"\")?\\s*(SELECT|INSERT|UPDATE|DELETE)\\b");

    @Test
    void sqlFilesNameOnlyTheirOwnSchema() throws IOException {
        Path root = Path.of("src/main/resources/sql");
        List<String> violations = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                    String ctx = root.relativize(f).getName(0).toString();
                    String sql = Files.readString(f).replaceAll("--[^\\n]*", "");
                    for (String other : SCHEMAS) {
                        if (!other.equals(ctx) && Pattern.compile("(?i)\\b" + other + "\\s*\\.\\s*[a-z_\"]").matcher(sql).find()) {
                            violations.add(f + " names schema " + other);
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void releaseTableQueriesBindTheRelease() throws IOException {
        Path root = Path.of("src/main/resources/sql");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("select_")).toList()) {
                String ctx = root.relativize(f).getName(0).toString();
                if (List.of("catalog", "harmonized", "observation", "provenance", "outlook", "fireinfo").contains(ctx)
                        && !Files.readString(f).contains("release_id = :releaseId")) {
                    violations.add(f.toString());
                }
            }
        }
        assertThat(violations).as("release-table selects without release_id = :releaseId").isEmpty();
    }

    @Test
    void javaSqlLiteralsOnlyInAdapterOut() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.toString().replace('\\', '/').contains("/adapter/out/")) {
                    continue;
                }
                if (DML_LITERAL.matcher(Files.readString(f)).find()) {
                    violations.add(f.toString());
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
