package io.emberline.platform.flyway;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Profile {@code flyway} (a pre-deploy Job): one Flyway per context schema — defaultSchema=&lt;ctx&gt;,
 * locations=db/migration/&lt;ctx&gt;, history table &lt;ctx&gt;.flyway_schema_history — then the process exits 0.
 * API pods never migrate ({@code spring.flyway.enabled=false}). Release partitions are created by the loaders, not here.
 */
@Component
@Profile("flyway")
public class MultiSchemaFlywayRunner implements CommandLineRunner {

    public static final List<String> SCHEMAS = List.of("catalog", "harmonized", "observation", "provenance", "ingest", "platform");
    private static final Logger log = LoggerFactory.getLogger(MultiSchemaFlywayRunner.class);

    private final DataSource dataSource;

    public MultiSchemaFlywayRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        migrateAll(dataSource);
    }

    public static void migrateAll(DataSource dataSource) {
        for (String schema : SCHEMAS) {
            MigrateResult result = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .table("flyway_schema_history")
                    .locations("classpath:db/migration/" + schema)
                    .failOnMissingLocations(true)
                    .load()
                    .migrate();
            log.info("flyway schema={} applied={} version={}", schema, result.migrationsExecuted, result.targetSchemaVersion);
        }
    }
}
