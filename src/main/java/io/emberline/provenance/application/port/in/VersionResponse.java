package io.emberline.provenance.application.port.in;

import io.emberline.shared.config.EmberlineProperties;

/**
 * /api/v1/version body. {@code release} is null before any activation. The top-level {@code method_version} and
 * {@code data_version} repeat the release block (contract schema Version) and are null without a release.
 */
public record VersionResponse(String apiVersion, String methodVersion, String dataVersion, ReleaseBlock release,
                              EmberlineProperties.Limits limits) {

    public record ReleaseBlock(String releaseKey, String methodVersion, String dataVersion) {
    }
}
