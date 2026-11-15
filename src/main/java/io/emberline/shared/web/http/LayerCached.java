package io.emberline.shared.web.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in of a v1.2 layer handler method to {@link HttpCachingAdvice#CACHE_CONTROL_LAYER} (contract header set
 * {@code layer_cached}). ETag, 304, X-Data-Version, X-Method-Version and Vary stay as on every release-pinned GET;
 * errors stay {@code no-store}. Methods without it keep their policy.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LayerCached {
}
