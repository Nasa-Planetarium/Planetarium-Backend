package io.emberline.acceptance.read;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.support.EmbeddedPg;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Same as AppTestBase (full app, random ports, fresh migrated database) but the API's release root is a temp copy of
 * core-v11 ({@code v11}; SIDO_11 renamed {@code =1+2} for the formula guard) plus the v1 fixture {@code core}: the API
 * reads harmonized_cell_day / residual / boundaries from the active release folder, so it must be the folder ingest
 * staged. (Not a subclass of AppTestBase: its dynamic emberline.release.root would be registered last and win.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.cors.allowed-origins=https://app.example",
                "emberline.ratelimit.enabled=false"})
abstract class ReadAppBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Path ROOT = V11Fixtures.newRoot("v11");
    static final Path V11 = ROOT.resolve("v11");

    static {
        V11Fixtures.rewrite(V11, "aoi_list.parquet",
                "SELECT * REPLACE (CASE WHEN aoi_id = 'SIDO_11' THEN '=1+2' ELSE name END AS name) FROM src");
        V11Fixtures.copyV1(ROOT, "core");
    }

    @LocalServerPort
    protected int port;

    @LocalManagementPort
    protected int managementPort;

    @Value("${spring.datasource.url}")
    protected String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("emberline.release.root", ROOT::toString);
    }

    protected HttpResponse<String> get(String path, String... headers) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET(), headers);
    }

    protected HttpResponse<String> getManagement(String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path)).GET());
    }

    protected HttpResponse<String> send(HttpRequest.Builder builder, String... headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            return HTTP.send(builder.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
