package io.opaa.auth;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.cors.*}: the browser origins besides the own one that may call the API and open the
 * WebSocket of speech input ({@code OPAA_CORS_ALLOWED_ORIGINS}, comma separated).
 *
 * @param allowedOrigins the origins, stripped and without empty entries; never {@code null}
 */
@InstallationWide
@NoDataImpact("browser origins of the API")
@ConfigurationProperties(prefix = "opaa.cors")
public record CorsProperties(
    @NoDataImpact("browser origins of the API") List<String> allowedOrigins) {

  public CorsProperties {
    allowedOrigins =
        allowedOrigins == null
            ? List.of()
            : allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
  }
}
