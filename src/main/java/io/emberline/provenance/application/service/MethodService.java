package io.emberline.provenance.application.service;

import io.emberline.provenance.application.port.in.GetMethodUseCase;
import io.emberline.provenance.application.port.in.MethodResponse;
import io.emberline.provenance.application.port.out.ProvenanceReadPort;
import io.emberline.provenance.domain.MethodDescription;
import io.emberline.provenance.domain.MethodMeta;
import io.emberline.shared.Units;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.error.ResourceNotFoundException;
import io.emberline.shared.json.RawJson;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.validation.Patterns;
import org.springframework.stereotype.Service;

@Service
class MethodService implements GetMethodUseCase {

    private final ProvenanceReadPort port;

    MethodService(ProvenanceReadPort port) {
        this.port = port;
    }

    @Override
    public MethodResponse current(ReleaseRef release) {
        MethodMeta meta = port.methodMeta(release.releaseId())
                .orElseThrow(() -> new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, "the method description is not loaded"));
        return new MethodResponse(release.methodVersion(), Units.UNIT, Units.UNIT_CODE, new RawJson(meta.metaJson()),
                MethodDescription.VALUE_SOURCES, MethodDescription.STATUSES, MethodDescription.VERDICT_RULE,
                MethodDescription.RULES, MethodDescription.SOURCES, release.dataVersion(), MethodDescription.DISPLAY_NAMES,
                MethodDescription.PERFORMANCE_COPY, new RawJson(meta.filesJson()),
                new MethodResponse.Ingest(release.loadedAt() == null ? null : release.loadedAt().toString(),
                        new RawJson(meta.aliasesUsedJson()), new RawJson(meta.warningsJson())));
    }

    @Override
    public MethodResponse byVersion(ReleaseRef release, String methodVersion) {
        if (methodVersion == null || !methodVersion.matches(Patterns.METHOD_VERSION)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "method_version", "method_version must match " + Patterns.METHOD_VERSION);
        }
        if (!methodVersion.equals(release.methodVersion())) {
            throw new ResourceNotFoundException(ErrorCode.ROUTE_NOT_FOUND, "method_version",
                    "method_version " + methodVersion + " is not the active release's method");
        }
        return current(release);
    }
}
