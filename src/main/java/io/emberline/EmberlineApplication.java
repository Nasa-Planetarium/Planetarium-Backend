package io.emberline;

import io.emberline.shared.cli.CliOutcome;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry points (one jar): API (default profile), {@code --spring.profiles.active=flyway} (migrate every schema and exit),
 * {@code --spring.profiles.active=ingest stage|activate|show <release_key>} (exit code = command result; the command's
 * JSON is the last stdout line).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EmberlineApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(EmberlineApplication.class, args);
        if (context.getEnvironment().matchesProfiles("ingest | flyway")) {
            String last = context.getBeanProvider(CliOutcome.class).stream().map(CliOutcome::lastLine).findFirst().orElse(null);
            int code = SpringApplication.exit(context);
            if (last != null) {
                System.out.println(last);
            }
            System.exit(code);
        }
    }
}
