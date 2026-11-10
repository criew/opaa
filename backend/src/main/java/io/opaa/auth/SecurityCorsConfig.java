package io.opaa.auth;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@InstallationWide
@NoDataImpact("browser origins of the API")
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityCorsConfig {

  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties cors) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(cors.allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Requested-With"));
    // ADR-0025: the SPA tells a token of an unknown (disabled) issuer apart from an expired one by
    // the error_description in this header - readable on the direct CORS path only when exposed.
    // Retry-After of a 429 tells the SPA how long to wait before it sends an upload again.
    config.setExposedHeaders(List.of(HttpHeaders.WWW_AUTHENTICATE, HttpHeaders.RETRY_AFTER));
    config.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
