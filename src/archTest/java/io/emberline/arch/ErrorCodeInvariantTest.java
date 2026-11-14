package io.emberline.arch;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** B0.17 — the ErrorCode enum is the contract's x-error-catalog, numbered DSSNN, titled in ko and en, carded in docs/errors. */
class ErrorCodeInvariantTest {

    static final Set<Integer> BANDS = Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
    static final Set<Integer> KINDS = Set.of(0, 1, 2, 3, 4, 5, 9);

    @Test
    void numbersAreUniqueFiveDigitsAndBanded() {
        Set<Integer> seen = new HashSet<>();
        for (ErrorCode c : ErrorCode.values()) {
            assertThat(seen.add(c.number())).as("unique %s", c).isTrue();
            assertThat(String.valueOf(c.number())).as("5 digits starting 1-9: %s", c).matches("^[1-9][0-9]{4}$");
            assertThat(BANDS).as("band of %s", c).contains(c.number() / 10000);
            assertThat(KINDS).as("kind SS of %s", c).contains((c.number() / 100) % 100);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void enumEqualsContractCatalog() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(200);
        Map<String, Object> doc;
        try (Reader r = Files.newBufferedReader(Path.of("contract/openapi.yaml"))) {
            doc = new Yaml(options).load(r);
        }
        Map<String, Object> errorNo = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas")).get("ErrorNo");
        List<Map<String, Object>> catalog = (List<Map<String, Object>>) errorNo.get("x-error-catalog");
        assertThat(catalog).hasSize(ErrorCode.values().length);
        for (Map<String, Object> row : catalog) {
            ErrorCode c = ErrorCode.valueOf((String) row.get("name"));
            assertThat(c.number()).as("number of %s", c).isEqualTo(row.get("no"));
            assertThat(c.code()).as("code of %s", c).isEqualTo(row.get("code"));
            Integer http = (Integer) row.get("http");
            if (http != null) {
                assertThat(c.httpStatus()).as("http of %s", c).isEqualTo(http);
            }
            assertThat(c.cliExit().orElse(null)).as("cli_exit of %s", c).isEqualTo(row.get("cli_exit"));
        }
    }

    @Test
    void everyConstantHasKoAndEnTitles() throws IOException {
        for (String lang : List.of("ko", "en")) {
            Properties p = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/messages_" + lang + ".properties")) {
                assertThat(in).as("messages_%s.properties", lang).isNotNull();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (ErrorCode c : ErrorCode.values()) {
                assertThat(p.getProperty(c.titleKey())).as("%s title %s", lang, c).isNotBlank();
            }
        }
    }

    @Test
    void everyConstantHasADocsErrorsCard() throws IOException {
        String all;
        try (Stream<Path> files = Files.list(Path.of("docs/errors"))) {
            all = files.filter(f -> f.toString().endsWith(".md")).map(ErrorCodeInvariantTest::read).collect(Collectors.joining("\n"));
        }
        for (ErrorCode c : ErrorCode.values()) {
            assertThat(all).as("card for %s", c).contains(c.name()).contains(String.valueOf(c.number()));
        }
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).distinct().allMatch(code ->
                Files.exists(Path.of("docs/errors", code + ".md")))).as("one card file per code string").isTrue();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
