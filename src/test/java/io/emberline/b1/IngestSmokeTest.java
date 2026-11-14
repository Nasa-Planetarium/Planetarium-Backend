package io.emberline.b1;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import io.emberline.support.ReleaseFixtures;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IngestSmokeTest {

    @Test
    void stageAndActivateCore() {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        Path root = ReleaseFixtures.newRoot();
        IngestCli.Result staged = IngestCli.run(url, root, "stage", "core");
        System.out.println("STAGE " + staged.json());
        assertThat(staged.exit()).isZero();
        IngestCli.Result active = IngestCli.run(url, root, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256());
        System.out.println("ACTIVATE " + active.json());
        assertThat(active.exit()).isZero();
    }
}
