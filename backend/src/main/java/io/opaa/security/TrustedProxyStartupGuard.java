package io.opaa.security;

import io.opaa.api.RateLimitProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * ADR-0033, Entscheidung 9: an empty {@code OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS} is the secure
 * default for a backend without a reverse proxy, but behind one its failure is silent - every
 * client shares the proxy's address and one bucket per limit - so every start in the {@code oidc}
 * profile says so at {@code WARN}. A wildcard range is refused when {@link RateLimitProperties}
 * binds, in every profile. The {@code dev} profile has no local sign-in and is not guarded.
 */
@Configuration
@Profile("oidc")
@EnableConfigurationProperties(RateLimitProperties.class)
public class TrustedProxyStartupGuard {

  private static final Logger log = LoggerFactory.getLogger(TrustedProxyStartupGuard.class);

  private final RateLimitProperties properties;

  public TrustedProxyStartupGuard(RateLimitProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void warnWhenNoProxyIsTrusted() {
    if (properties.trustedProxyCidrs().isEmpty()) {
      log.warn(
          "opaa.rate-limit.trusted-proxy-cidrs ({}) is empty: X-Forwarded-For is ignored and every"
              + " request counts under the address of its connection. Correct for a backend without"
              + " a reverse proxy. Behind one - the Compose stack's nginx included - every client"
              + " shares one bucket per limit (the sign-in limit then applies to the whole house)"
              + " and OPAA_LOCAL_ADMIN_ALLOWED_CIDRS sees the proxy's address instead of the"
              + " client's. Set the variable to the proxy's network (ADR-0033).",
          RateLimitProperties.TRUSTED_PROXY_CIDRS_VARIABLE);
    }
  }
}
