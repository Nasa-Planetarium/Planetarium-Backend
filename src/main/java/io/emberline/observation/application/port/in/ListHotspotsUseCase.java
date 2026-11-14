package io.emberline.observation.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface ListHotspotsUseCase {

    /** Points of harmonized_hotspots; the record type is decided when the release ships the file. */
    HotspotList hotspots(ReleaseRef release, HotspotsQuery query);
}
