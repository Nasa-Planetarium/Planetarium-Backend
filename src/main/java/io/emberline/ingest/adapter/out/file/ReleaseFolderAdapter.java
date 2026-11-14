package io.emberline.ingest.adapter.out.file;

import io.emberline.ingest.application.port.out.ReleaseFolderPort;
import io.emberline.ingest.domain.Manifest;
import io.emberline.ingest.domain.ReleaseKey;
import io.emberline.shared.config.EmberlineProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only access to {@code EMBERLINE_RELEASE_ROOT/<key>}: the resolved path must stay inside the root (also after
 * resolving symlinks); nothing is ever created there. SHA256SUMS lines are {@code <64 hex>  <file name>}.
 */
@Component
class ReleaseFolderAdapter implements ReleaseFolderPort {

    private static final Pattern LINE = Pattern.compile("^([0-9a-fA-F]{64}) [ *]([A-Za-z0-9._-]+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Optional<Path> root;

    ReleaseFolderAdapter(EmberlineProperties properties) {
        this.root = properties.release().rootPath();
    }

    @Override
    public Optional<Path> resolve(ReleaseKey key) {
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Path base = root.get();
        Path dir = base.resolve(key.value()).normalize();
        if (!dir.startsWith(base) || dir.equals(base) || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try {
            Path realBase = base.toRealPath();
            Path real = dir.toRealPath();
            return real.startsWith(realBase) ? Optional.of(real) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Manifest> manifest(Path dir) {
        Path file = dir.resolve("SHA256SUMS");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            Map<String, String> files = new LinkedHashMap<>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                Matcher m = LINE.matcher(line.strip());
                if (!m.matches()) {
                    return Optional.empty();
                }
                files.put(m.group(2), m.group(1).toLowerCase(java.util.Locale.ROOT));
            }
            return Optional.of(new Manifest(files, hex(bytes)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<String> methodVersion(Path dir) {
        try {
            JsonNode meta = JSON.readTree(Files.readString(dir.resolve("m2v_meta.json")));
            JsonNode mv = meta.get("method_version");
            return mv != null && mv.isString() ? Optional.of(mv.asString()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
