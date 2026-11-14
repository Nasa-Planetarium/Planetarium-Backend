package io.emberline.ingest.application.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Polling of ingest.active_release runs in API pods only (off in the flyway / ingest profiles). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "emberline.release.polling-enabled", havingValue = "true", matchIfMissing = true)
class ReleasePollingConfig {
}
