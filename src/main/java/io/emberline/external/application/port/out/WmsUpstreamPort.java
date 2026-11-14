package io.emberline.external.application.port.out;

import io.emberline.external.domain.FirmsKey;
import io.emberline.external.domain.WmsTile;

/** FIRMS WMS GetMap. Failures are UpstreamException 60501 / 60502 without the upstream body or URL. */
public interface WmsUpstreamPort {

    byte[] fetch(WmsTile tile, FirmsKey key);
}
