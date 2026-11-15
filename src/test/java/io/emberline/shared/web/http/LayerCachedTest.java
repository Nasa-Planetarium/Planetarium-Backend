package io.emberline.shared.web.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.web.error.GlobalExceptionHandler;
import io.emberline.shared.web.error.ProblemFactory;
import io.emberline.shared.web.release.ReleaseRefFilter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * a {@link LayerCached} handler gets the contract CacheControlLayer header plus the usual
 * ETag / 304 / X-Data-Version / X-Method-Version / Vary; its errors stay no-store; a handler without the annotation
 * keeps the data policy.
 */
class LayerCachedTest {

    static final String LAYER = "public, max-age=0, s-maxage=3600, must-revalidate";
    static final String DATA = "public, max-age=60, s-maxage=600";
    static final ReleaseRef RELEASE = new ReleaseRef(7L, "core", "m2v-test", "core#0123456789ab", Instant.EPOCH);

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new HttpCachingAdvice(), new GlobalExceptionHandler(new ProblemFactory(
                    new StaticMessageSource(), new AcceptHeaderLocaleResolver(),
                    new EmberlineProperties(null, null, null, null, null))))
            .build();

    @Test
    void layerHandlerGetsTheLayerPolicyAndTheValidators() throws Exception {
        mvc.perform(get("/api/v1/test-layer").requestAttr(ReleaseRefFilter.ATTRIBUTE, RELEASE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", LAYER))
                .andExpect(header().string("ETag", matchesPattern("^\"[0-9a-f]{32}\"$")))
                .andExpect(header().string("X-Data-Version", "core#0123456789ab"))
                .andExpect(header().string("X-Method-Version", "m2v-test"))
                .andExpect(header().string("Vary", "Accept, Accept-Language"));
        assertThat(HttpCachingAdvice.CACHE_CONTROL_LAYER).isEqualTo(LAYER);
    }

    @Test
    void ifNoneMatchGives304WithTheLayerPolicy() throws Exception {
        String etag = mvc.perform(get("/api/v1/test-layer").requestAttr(ReleaseRefFilter.ATTRIBUTE, RELEASE))
                .andReturn().getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        mvc.perform(get("/api/v1/test-layer").requestAttr(ReleaseRefFilter.ATTRIBUTE, RELEASE).header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(header().string("Cache-Control", LAYER))
                .andExpect(content().string(""));
    }

    /** F6: SCHEMA_VERSION is s4 since the shape changes, the compare fields and the /version limits; a client's s3 ETag of the same request is a 200 with a body. */
    @Test
    void anOldSchemaEtagIsNotAMatch() throws Exception {
        MvcResult first = mvc.perform(get("/api/v1/test-layer").requestAttr(ReleaseRefFilter.ATTRIBUTE, RELEASE)).andReturn();
        String served = first.getResponse().getHeader("ETag");
        MediaType json = MediaType.parseMediaType(first.getResponse().getContentType());
        assertThat(HttpCachingAdvice.SCHEMA_VERSION).isEqualTo("s4");
        assertThat(served).isEqualTo("\"" + HttpCachingAdvice.etag(first.getRequest(), json, RELEASE, "s4") + "\"");
        String old = "\"" + HttpCachingAdvice.etag(first.getRequest(), json, RELEASE, "s3") + "\"";
        assertThat(old).isNotEqualTo(served);
        mvc.perform(get("/api/v1/test-layer").requestAttr(ReleaseRefFilter.ATTRIBUTE, RELEASE).header("If-None-Match", old))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", served))
                .andExpect(content().string("{\"value\":1}"));
        mvc.perform(get("/api/v1/test-layer").requestAttr(ReleaseRefFilter.ATTRIBUTE, RELEASE).header("If-None-Match", served))
                .andExpect(status().isNotModified());
    }

    @Test
    void layerErrorStaysNoStore() throws Exception {
        mvc.perform(get("/api/v1/test-layer").param("mode", "missing").requestAttr(ReleaseRefFilter.ATTRIBUTE, RELEASE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().doesNotExist("ETag"));
    }

    @Test
    void handlerWithoutTheAnnotationKeepsTheDataPolicy() throws Exception {
        mvc.perform(get("/api/v1/test-data").requestAttr(ReleaseRefFilter.ATTRIBUTE, RELEASE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", DATA))
                .andExpect(header().exists("ETag"));
    }

    /** Test-only routes; nested in a test class, so the application's component scan never picks it up. */
    @RestController
    public static class TestController {

        @LayerCached
        @GetMapping("/api/v1/test-layer")
        public Sample layer(@RequestParam(name = "mode", defaultValue = "ok") String mode) {
            if ("missing".equals(mode)) {
                throw new DataUnavailableException(ErrorCode.LAYER_NOT_LOADED, "layer is not in the active release");
            }
            return new Sample(1);
        }

        @GetMapping("/api/v1/test-data")
        public Sample data() {
            return new Sample(2);
        }
    }

    public record Sample(int value) {
    }
}
