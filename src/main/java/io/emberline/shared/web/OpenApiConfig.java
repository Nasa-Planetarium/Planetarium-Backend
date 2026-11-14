package io.emberline.shared.web;

import io.emberline.shared.release.PinnedRelease;
import io.emberline.shared.release.ReleaseRef;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/** springdoc must list exactly the contract's /api/v1 parameters: the pinned release is not a request parameter. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(ReleaseRef.class, PinnedRelease.class);
    }
}
