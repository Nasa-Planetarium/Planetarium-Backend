package io.emberline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry points (one jar): API (default profile), {@code --spring.profiles.active=flyway} (migrate every schema and exit),
 * {@code --spring.profiles.active=ingest stage|activate|show <release_key>} (exit code = command result).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EmberlineApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(EmberlineApplication.class, args);
        if (context.getEnvironment().matchesProfiles("ingest | flyway")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
