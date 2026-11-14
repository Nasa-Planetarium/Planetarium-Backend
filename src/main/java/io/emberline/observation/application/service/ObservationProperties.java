package io.emberline.observation.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code emberline.observation.*}.
 *
 * @param aoiPrefilterRows most rows read from an AOI's bbox before the center-in-boundary test; more → 40103
 */
@ConfigurationProperties("emberline.observation")
record ObservationProperties(Integer aoiPrefilterRows) {

    ObservationProperties {
        aoiPrefilterRows = aoiPrefilterRows == null || aoiPrefilterRows <= 0 ? 200_000 : aoiPrefilterRows;
    }
}
