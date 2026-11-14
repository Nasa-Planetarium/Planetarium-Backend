package io.emberline.harmonized.adapter.in.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CSV as a representation of the same resource: {@code Accept: text/csv} or {@code ?format=csv} (format=json is the
 * default JSON), written by {@link CsvTableHttpMessageConverter}.
 */
@Configuration(proxyBeanMethods = false)
class HarmonizedWebConfig implements WebMvcConfigurer {

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.favorParameter(true).parameterName("format")
                .mediaType("json", MediaType.APPLICATION_JSON)
                .mediaType("csv", new MediaType("text", "csv"));
    }

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.addCustomConverter(new CsvTableHttpMessageConverter());
    }
}
