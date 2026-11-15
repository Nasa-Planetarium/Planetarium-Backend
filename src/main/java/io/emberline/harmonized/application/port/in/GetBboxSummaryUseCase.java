package io.emberline.harmonized.application.port.in;

/**
 * GET /api/v1/summaries — bbox sums re-aggregate points; openapi marks the operation {@code x-status: release-absent},
 * so after the parameters are checked it answers 503 data_not_loaded.
 */
public interface GetBboxSummaryUseCase {

    SummaryResponse summary(BboxSummaryQuery query);
}
