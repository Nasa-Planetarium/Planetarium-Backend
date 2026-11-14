package io.emberline.external.adapter.out.firms;

import io.emberline.external.application.port.out.FirmsKeyPort;
import io.emberline.external.application.service.FirmsProperties;
import io.emberline.external.domain.FirmsKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the FIRMS key from the Secret file named by {@code emberline.firms.map-key-file} (FIRMS_MAP_KEY_FILE,
 * configtree style: the file content is the key). Re-read when the file changes (Secret rotation); the key is
 * registered with the log masking before it is used. No env var holds the key, and nothing logs its value.
 */
@Component
class FirmsKeyProvider implements FirmsKeyPort {

    private static final Logger log = LoggerFactory.getLogger(FirmsKeyProvider.class);
    private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");

    private final Path file;
    private volatile FileTime loadedAt;
    private volatile Optional<FirmsKey> key = Optional.empty();

    FirmsKeyProvider(FirmsProperties properties) {
        String p = properties.mapKeyFile();
        this.file = p == null || p.isBlank() ? null : Path.of(p);
    }

    @Override
    public Optional<FirmsKey> key() {
        if (file == null) {
            return Optional.empty();
        }
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            if (!modified.equals(loadedAt)) {
                String value = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (SHAPE.matcher(value).matches()) {
                    KeyMaskingTurboFilter.register(value);
                    key = Optional.of(new FirmsKey(value));
                } else {
                    key = Optional.empty();
                    log.warn("FIRMS key file has no usable key");
                }
                loadedAt = modified;
            }
            return key;
        } catch (IOException | RuntimeException e) {
            log.warn("FIRMS key file is not readable ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
