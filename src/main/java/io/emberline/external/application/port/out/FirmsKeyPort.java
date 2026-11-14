package io.emberline.external.application.port.out;

import io.emberline.external.domain.FirmsKey;
import java.util.Optional;

/** The FIRMS key from the Secret file (FIRMS_MAP_KEY_FILE); empty when none is configured or readable. */
public interface FirmsKeyPort {

    Optional<FirmsKey> key();
}
