package io.emberline.ingest.application.service;

import io.emberline.ingest.application.port.in.IngestOutcome;
import io.emberline.ingest.application.port.in.ShowReleaseUseCase;
import io.emberline.ingest.application.port.out.ReleaseRepository;
import io.emberline.ingest.domain.ReleaseKey;
import io.emberline.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class ReleaseQueryService implements ShowReleaseUseCase {

    private final ReleaseRepository releases;

    ReleaseQueryService(ReleaseRepository releases) {
        this.releases = releases;
    }

    @Override
    public IngestOutcome show(String rawKey) {
        ReleaseKey key = new ReleaseKey(rawKey);
        return releases.findByKey(key).map(r -> Outcomes.of(r, Map.of(), 0))
                .orElseGet(() -> Outcomes.failed(key.value(), "ABSENT", ErrorCode.RELEASE_INVALID, List.of("RELEASE_NOT_FOUND")));
    }
}
