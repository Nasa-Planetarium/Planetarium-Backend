package io.emberline.shared.sql;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL lives in {@code src/main/resources/sql/<ctx>/*.sql} (never in Java string literals). Values are always bind
 * parameters; {@code {placeholder}} is only used for identifiers the code builds itself (partition names, columns).
 */
public final class SqlResource {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private SqlResource() {
    }

    public static String text(String path) {
        return CACHE.computeIfAbsent(path, SqlResource::read);
    }

    /** Statements of a file separated by {@code ;} at line end, comments removed. */
    public static List<String> statements(String path) {
        String noComments = text(path).replaceAll("(?m)--[^\\n]*$", "");
        return Arrays.stream(noComments.split(";\\s*(\\n|$)")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String read(String path) {
        try (InputStream in = SqlResource.class.getResourceAsStream("/sql/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing SQL resource sql/" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
