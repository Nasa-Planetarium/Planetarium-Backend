package io.emberline.shared.web.security;

import io.emberline.shared.config.EmberlineProperties;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Public read chain: anonymous, STATELESS (no session, no cookie), CSRF off (no cookie auth exists), Spring Security's
 * default Cache-Control off (HttpCachingAdvice owns it), CORS only for the configured origins.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PublicApiSecurityConfig {

    @Bean
    public SecurityFilterChain publicApiChain(HttpSecurity http, EmberlineProperties properties) throws Exception {
        http.securityMatcher("/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .securityContext(c -> c.requireExplicitSave(true))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .headers(h -> h.cacheControl(c -> c.disable()))
                .cors(c -> c.configurationSource(cors(properties)));
        return http.build();
    }

    static CorsConfigurationSource cors(EmberlineProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "HEAD", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Accept", "Accept-Language", "Content-Type", "If-None-Match", "X-Request-Id"));
        config.setExposedHeaders(List.of("ETag", "X-Request-Id", "X-Method-Version", "X-Data-Version", "Link", "Retry-After",
                "X-Truncated", "X-Total-Count", "X-Next-Cursor"));
        config.setAllowCredentials(false);
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** No accounts in S1; replaces Boot's generated in-memory user (and its logged password). */
    @Bean
    public UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }
}
