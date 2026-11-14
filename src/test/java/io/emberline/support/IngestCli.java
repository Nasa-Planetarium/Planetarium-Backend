package io.emberline.support;

import io.emberline.EmberlineApplication;
import io.emberline.shared.cli.CliOutcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs the ingest profile in-process (same wiring as {@code java -jar … --spring.profiles.active=ingest}). */
public final class IngestCli {

    /** Exit code and the JSON line the CLI prints last. */
    public record Result(int exit, JsonNode json) {
        public String state() {
            return json.get("state").asString();
        }

        public List<String> reasons() {
            List<String> out = new ArrayList<>();
            json.get("reason_codes").forEach(n -> out.add(n.asString()));
            return out;
        }

        public List<String> aliases() {
            List<String> out = new ArrayList<>();
            json.get("aliases_used").forEach(n -> out.add(n.asString()));
            return out;
        }
    }

    private IngestCli() {
    }

    public static Result run(String jdbcUrl, Path releaseRoot, String... command) {
        List<String> args = new ArrayList<>(List.of("--spring.profiles.active=ingest", "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=postgres", "--emberline.release.root=" + releaseRoot,
                "--emberline.ingest.copy-workers=2", "--spring.main.banner-mode=off"));
        args.addAll(List.of(command));
        SpringApplication app = new SpringApplication(EmberlineApplication.class);
        try (ConfigurableApplicationContext ctx = app.run(args.toArray(new String[0]))) {
            CliOutcome outcome = ctx.getBean(CliOutcome.class);
            int exit = SpringApplication.exit(ctx);
            return new Result(exit, JsonMapper.builder().build().readTree(outcome.lastLine()));
        }
    }
}
