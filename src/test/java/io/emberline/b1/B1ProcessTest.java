package io.emberline.b1;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.EmberlineApplication;
import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.ReleaseFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The real entry point in a child JVM: exit codes and "the last stdout line is the JSON outcome". */
class B1ProcessTest {

    record Run(int exit, String lastLine) {
    }

    static Run main(String url, Path root, String... command) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx512m", "-cp", System.getProperty("java.class.path"), EmberlineApplication.class.getName(),
                "--spring.profiles.active=ingest", "--spring.datasource.url=" + url, "--spring.datasource.username=postgres"));
        cmd.addAll(List.of(command));
        Path out = Files.createTempFile("emberline-ingest", ".out");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile());
        pb.environment().put("EMBERLINE_RELEASE_ROOT", root.toString());
        Process p = pb.start();
        assertThat(p.waitFor(180, TimeUnit.SECONDS)).isTrue();
        List<String> lines = Files.readAllLines(out).stream().filter(l -> !l.isBlank()).toList();
        return new Run(p.exitValue(), lines.isEmpty() ? "" : lines.get(lines.size() - 1));
    }

    @Test
    void cliExitCodesAndLastLineJson() throws Exception {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        Path root = ReleaseFixtures.newRoot();
        JsonMapper json = JsonMapper.builder().build();

        Run stage = main(url, root, "stage", "core");
        assertThat(stage.exit()).as(stage.lastLine()).isZero();
        JsonNode staged = json.readTree(stage.lastLine());
        assertThat(staged.get("release_key").asString()).isEqualTo("core");
        assertThat(staged.get("state").asString()).isEqualTo("LOADED");
        assertThat(staged.get("reason_codes").isArray()).isTrue();
        assertThat(staged.get("aliases_used").isArray()).isTrue();

        Run activate = main(url, root, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256());
        assertThat(activate.exit()).isZero();
        assertThat(json.readTree(activate.lastLine()).get("state").asString()).isEqualTo("ACTIVE");

        Run evil = main(url, root, "stage", "../evil");
        assertThat(evil.exit()).isEqualTo(3);
        assertThat(json.readTree(evil.lastLine()).get("reason_codes").get(0).asString()).isEqualTo("RELEASE_KEY_INVALID");

        Run mismatch = main(url, root, "activate", "core", "--expect-sha256", "f".repeat(64));
        assertThat(mismatch.exit()).isEqualTo(4);
    }
}
