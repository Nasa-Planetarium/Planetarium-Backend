package io.emberline.provenance.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface GetSatelliteStatusUseCase {

    SatelliteStatusResponse status(ReleaseRef release, SatelliteStatusQuery query);
}
