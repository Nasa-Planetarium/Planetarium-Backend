package io.emberline.support;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Reads contract/openapi.yaml (the canonical contract) for assertions. */
public final class Contract {

    private static Map<String, Object> doc;

    private Contract() {
    }

    @SuppressWarnings("unchecked")
    public static synchronized Map<String, Object> openapi() {
        if (doc == null) {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(200);
            try (Reader r = Files.newBufferedReader(Path.of("contract/openapi.yaml"))) {
                doc = new Yaml(options).load(r);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> at(String... keys) {
        Object node = openapi();
        for (String k : keys) {
            node = ((Map<String, Object>) node).get(k);
        }
        return (Map<String, Object>) node;
    }

    /** Version.limits of the contract: each property's first example value (the contract has no separate x-limits block). */
    @SuppressWarnings("unchecked")
    public static Map<String, Integer> limits() {
        Map<String, Object> props = at("components", "schemas", "Version", "properties", "limits", "properties");
        Map<String, Integer> out = new java.util.TreeMap<>();
        props.forEach((k, v) -> out.put(k, (Integer) ((List<Object>) ((Map<String, Object>) v).get("examples")).get(0)));
        return out;
    }
}
