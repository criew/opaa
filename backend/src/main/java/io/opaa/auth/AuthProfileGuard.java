package io.opaa.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Refuses to start when no authentication profile is active.
 *
 * <p>Every authentication mode contributes its own {@code SecurityFilterChain} ({@link
 * OidcSecurityConfig}, {@link DevSecurityConfig}). Without one of them no chain guards {@code
 * /api/**} at all: the unconditional chain of the S3 event intake ({@link S3EventSecurityConfig})
 * already backs Spring Boot's generic security auto-configuration off, so the application would
 * start open instead of behind a generated password. Failing loudly at startup beats either.
 */
@Configuration
@Profile("!oidc & !dev")
public class AuthProfileGuard {

  @PostConstruct
  void rejectMissingAuthProfile() {
    throw new IllegalStateException(
        """
        No authentication profile is active. Set SPRING_PROFILES_ACTIVE to include either \
        "oidc" (production) or "dev" (local development and tests). See docs/handbuch/deployment.md.""");
  }
}
