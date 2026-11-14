package io.emberline.shared.web;

import io.emberline.shared.json.RawJson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/** Writes {@link RawJson} verbatim (stored m2v_meta.json, GeoJSON geometry) — values as the release has them. */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public SimpleModule emberlineJsonModule() {
        SimpleModule module = new SimpleModule("emberline");
        module.addSerializer(RawJson.class, new ValueSerializer<>() {
            @Override
            public void serialize(RawJson value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeRawValue(value.json());
            }
        });
        return module;
    }
}
