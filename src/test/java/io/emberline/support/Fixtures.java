package io.emberline.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** The owner fixture (src/test/resources/release/core, real values) and derived release folders for tests. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Path releaseRoot() {
        return Path.of("src/test/resources/release").toAbsolutePath().normalize();
    }

    public static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** sha256 of the core fixture manifest (SHA256SUMS bytes) — the value passed to activate --expect-sha256. */
    public static String coreManifestSha256() {
        return sha256(releaseRoot().resolve("core/SHA256SUMS"));
    }
}
