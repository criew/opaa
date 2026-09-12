package io.opaa.auth.local;

import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.SecretValidator;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Refuses to start the {@code oidc} profile without a strong {@code OPAA_AUTH_JWT_SECRET}
 * (ADR-0033, Entscheidung 6) - the same fail-fast shape as {@link io.opaa.auth.AuthProfileGuard}.
 * Every key of the local issuer is derived from this secret; a missing or placeholder value would
 * leave every local session forgeable. The {@code dev} profile has no local sign-in and is not
 * guarded. The check is the {@link io.opaa.security.ValidSecret} constraint on {@link
 * LocalAuthProperties#jwtSecret()}, evaluated here rather than at binding time so that only this
 * profile pays for it.
 */
@Configuration
@Profile("oidc")
public class LocalAuthSecretGuard {

  private static final Logger log = LoggerFactory.getLogger(LocalAuthSecretGuard.class);

  private final LocalAuthProperties properties;

  public LocalAuthSecretGuard(LocalAuthProperties properties) {
    this.properties = properties;
  }

  /**
   * ADR-0033, Entscheidung 7: {@code cookie-secure = false} is allowed for local HTTP, but every
   * start in this profile says so - the refresh cookie then travels without {@code Secure}.
   */
  @PostConstruct
  void warnAboutInsecureCookie() {
    if (!Boolean.TRUE.equals(properties.cookieSecure())) {
      log.warn(
          "opaa.auth.local.cookie-secure is false (OPAA_AUTH_LOCAL_COOKIE_SECURE): the refresh"
              + " cookie opaa_refresh is set without the Secure attribute and travels over plain"
              + " HTTP. Acceptable for local development only - set it to true behind TLS"
              + " (ADR-0033).");
    }
  }

  @PostConstruct
  void rejectWeakSecret() {
    Set<ConstraintViolation<LocalAuthProperties>> violations;
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      violations = factory.getValidator().validate(properties);
    }
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "Local accounts cannot start: "
              + SecretValidator.describeRequirement(LocalAuthKeyService.SECRET_VARIABLE)
              + " The \"oidc\" profile refuses to start without it (ADR-0033); the \"dev\""
              + " profile does not need it. See docs/handbuch/deployment.md.");
    }
  }
}
