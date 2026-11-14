package io.emberline.provenance.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface GetMethodUseCase {

    MethodResponse current(ReleaseRef release);

    /** The method of the pinned release when its method_version matches; 404 otherwise. */
    MethodResponse byVersion(ReleaseRef release, String methodVersion);
}
