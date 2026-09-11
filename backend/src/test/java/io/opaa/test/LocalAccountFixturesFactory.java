package io.opaa.test;

import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalCredentialsRepository;
import io.opaa.auth.local.LocalRefreshTokenRepository;
import io.opaa.auth.local.LocalRevokedTokenRepository;
import io.opaa.auth.oidc.OidcProviderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds {@link LocalAccountFixtures} from the application's own beans. A regular {@code
 * @Component} under {@code src/test}, picked up by every {@code @SpringBootTest} of the {@code
 * io.opaa} package - not a {@code @TestConfiguration}, so importing it is never necessary and no
 * context's cache key changes.
 */
@Component
public class LocalAccountFixturesFactory {

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalRefreshTokenRepository refreshTokens;
  private final LocalRevokedTokenRepository revokedTokens;
  private final OidcProviderRepository providers;
  private final PasswordEncoder passwordEncoder;
  private final PlatformTransactionManager transactionManager;
  private final ApplicationEventPublisher events;
  private final JdbcTemplate jdbc;

  public LocalAccountFixturesFactory(
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalRefreshTokenRepository refreshTokens,
      LocalRevokedTokenRepository revokedTokens,
      OidcProviderRepository providers,
      PasswordEncoder passwordEncoder,
      PlatformTransactionManager transactionManager,
      ApplicationEventPublisher events,
      JdbcTemplate jdbc) {
    this.users = users;
    this.credentials = credentials;
    this.refreshTokens = refreshTokens;
    this.revokedTokens = revokedTokens;
    this.providers = providers;
    this.passwordEncoder = passwordEncoder;
    this.transactionManager = transactionManager;
    this.events = events;
    this.jdbc = jdbc;
  }

  public LocalAccountFixtures create() {
    return new LocalAccountFixtures(
        users,
        credentials,
        refreshTokens,
        revokedTokens,
        providers,
        passwordEncoder,
        new TransactionTemplate(transactionManager),
        events,
        jdbc);
  }
}
