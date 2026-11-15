package io.emberline.fireinfo.adapter.in.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the fire-info text/csv writer ({@code format=csv} negotiation is the application's, see HarmonizedWebConfig). */
@Configuration(proxyBeanMethods = false)
class FireInfoWebConfig implements WebMvcConfigurer {

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.addCustomConverter(new LayerCsvHttpMessageConverter());
    }
}
