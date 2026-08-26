package io.opaa.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Refuses to start when no authentication profile is active.
 *
 * <p>Every authentication mode contributes its own {@code SecurityFilterChain} ({@link
 * OidcSecurityConfig}, {@link DevSecurityConfig}). Without one of them Spring Boot's generic
 * security auto-configuration takes over and locks the application behind a randomly generated
 * password — an unusable state that used to be the shipped default and cost a considerable amount
 * of confusion. Failing loudly at startup beats a deployment that appears to be running.
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
