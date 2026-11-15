package io.emberline.outlook.application.port.in;

/**
 * POST /api/v1/forecast (FireOutlook Planner): the forecast service's JSON answer for a validated query, byte for
 * byte as the service wrote it (never deserialized and written again).
 */
public interface ForecastUseCase {

    byte[] forecast(ForecastQuery query);
}
