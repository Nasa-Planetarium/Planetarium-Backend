package io.emberline.outlook.adapter.in.web;

import io.emberline.outlook.application.port.in.ForecastUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FireOutlook Planner 30-day outlook (tag outlook; model contract {@code POST /forecast}). A read-only computation
 * behind a POST: the body is capped and marked no-store by {@link ForecastRequestFilter}, validated by
 * {@link ForecastBodyReader}, and the forecast service's JSON answer is written as received. The hand-off alias
 * {@code /api/forecast} forwards here.
 */
@RestController
@RequestMapping(path = "/api/v1")
class ForecastController {

    private final ForecastUseCase forecast;
    private final ForecastBodyReader reader;

    ForecastController(ForecastUseCase forecast, ForecastBodyReader reader) {
        this.forecast = forecast;
        this.reader = reader;
    }

    @PostMapping(path = "/forecast", consumes = "application/json", produces = "application/json")
    byte[] forecast(@RequestBody(required = false) byte[] body) {
        return forecast.forecast(reader.read(body));
    }
}
