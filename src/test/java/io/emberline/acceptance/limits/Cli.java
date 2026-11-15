package io.emberline.acceptance.limits;

import io.emberline.EmberlineApplication;
import io.emberline.shared.cli.CliOutcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** The ingest CLI in-process with the redis-durable URL (where the release.activated relay XADDs). */
final class Cli {

    private Cli() {
    }

    static int run(String jdbcUrl, Path root, String durableUrl, String... command) {
        List<String> args = new ArrayList<>(List.of("--spring.profiles.active=ingest", "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=postgres", "--emberline.release.root=" + root,
                "--emberline.ingest.copy-workers=2", "--emberline.events.durable-url=" + durableUrl, "--emberline.events.env=test"));
        args.addAll(List.of(command));
        try (ConfigurableApplicationContext ctx = new SpringApplication(EmberlineApplication.class).run(args.toArray(new String[0]))) {
            ctx.getBean(CliOutcome.class);
            return SpringApplication.exit(ctx);
        }
    }
}
