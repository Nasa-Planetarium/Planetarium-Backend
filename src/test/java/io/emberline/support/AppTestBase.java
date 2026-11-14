package io.emberline.support;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Full application on random ports (public + management) over a fresh migrated database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.cors.allowed-origins=https://app.example"})
public abstract class AppTestBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    protected static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

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
        registry.add("emberline.release.root", () -> Fixtures.releaseRoot().toString());
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
