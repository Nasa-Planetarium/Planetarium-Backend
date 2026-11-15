package io.emberline.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * pl-v12b: deploy.sh waits for the load Job by polling its conditions. The {@code wait_load_job} function is cut out of
 * deploy/deploy.sh as it stands and run under bash with a fake {@code kubectl} on PATH that prints the Job's True
 * conditions. (deploy.sh is read at run time, not a Gradle input: rerun this class with --rerun after editing it.)
 */
class DeployLoadWaitTest {

    @TempDir
    Path dir;

    record Run(int exit, Duration took, String stderr) {
    }

    static String function() throws IOException {
        String sh = Files.readString(Path.of("deploy/deploy.sh"));
        int start = sh.indexOf("wait_load_job() {");
        assertThat(start).as("wait_load_job in deploy.sh").isNotNegative();
        return sh.substring(start, sh.indexOf("\n}\n", start) + 3);
    }

    Run run(String conditions, int ceiling) throws IOException, InterruptedException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, "#!/bin/bash\nprintf '%b' \"$FAKE_CONDITIONS\"\n");
        Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path script = dir.resolve("wait.sh");
        Files.writeString(script, "set -euo pipefail\nNS=planetarium\n" + function() + "wait_load_job pl-load-v1-2-test " + ceiling + "\n");
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE_CONDITIONS", conditions);
        pb.environment().put("LOAD_POLL_SECONDS", "0.2");
        long t0 = System.nanoTime();
        Process p = pb.start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("wait_load_job returned within 60 s").isTrue();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.exitValue(), Duration.ofNanos(System.nanoTime() - t0), err);
    }

    @Test
    void failedLoadJobReturnsNonZeroAtOnceUnderTheCeiling() throws Exception {
        Run r = run("FailureTarget\\nFailed\\n", 3600);
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.took()).isLessThan(Duration.ofSeconds(30));
        assertThat(r.stderr()).contains("job/pl-load-v1-2-test failed");
    }

    @Test
    void completeLoadJobReturnsZero() throws Exception {
        Run r = run("SuccessCriteriaMet\\nComplete\\n", 3600);
        assertThat(r.exit()).isZero();
        assertThat(r.took()).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void runningLoadJobReturnsNonZeroAtTheCeiling() throws Exception {
        Run r = run("", 1);
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.stderr()).contains("neither Complete nor Failed after 1s");
    }

    @Test
    void deployScriptWaitsWithTheFunctionAndTheSameCeiling() throws IOException {
        String sh = Files.readString(Path.of("deploy/deploy.sh"));
        assertThat(sh).contains("wait_load_job \"pl-load-$SLUG\" 3600").doesNotContain("--for=condition=complete");
    }
}
