package io.emberline.provenance.application.service;

import io.emberline.provenance.application.port.in.GetVersionUseCase;
import io.emberline.provenance.application.port.in.VersionResponse;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.release.PinnedRelease;
import io.emberline.shared.release.ReleaseRef;
import org.springframework.stereotype.Service;

@Service
class VersionService implements GetVersionUseCase {

    static final String API_VERSION = "v1";

    private final EmberlineProperties properties;

    VersionService(EmberlineProperties properties) {
        this.properties = properties;
    }

    @Override
    public VersionResponse version(PinnedRelease release) {
        ReleaseRef ref = release.ref();
        VersionResponse.ReleaseBlock block = ref == null ? null
                : new VersionResponse.ReleaseBlock(ref.releaseKey(), ref.methodVersion(), ref.dataVersion());
        return new VersionResponse(API_VERSION, ref == null ? null : ref.methodVersion(),
                ref == null ? null : ref.dataVersion(), block, properties.limits());
    }
}
