package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.ProviderType;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.organization.Organization;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The JPA mapping of the local-account entities against the real Liquibase schema (changesets
 * 003-009, {@code ddl-auto=none}): every column the entities declare exists with the type the
 * database expects, the atomic repository updates return the row counts the issuer relies on
 * (#1533), and the crypto beans are present in the application context.
 */
@OpaaIntegrationTest
class LocalAccountPersistenceIntegrationTest {

  @Autowired private UserRepository userRepository;
  @Autowired private LocalCredentialsRepository credentialsRepository;
  @Autowired private LocalRefreshTokenRepository refreshTokenRepository;
  @Autowired private LocalRevokedTokenRepository revokedTokenRepository;
  @Autowired private LocalActionTokenRepository actionTokenRepository;
  @Autowired private LocalAuthSettingsRepository settingsRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private LocalAuthKeyService keyService;

  private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private User user;

  @BeforeEach
  void setUp() {
    cleanUp();
    user = userRepository.save(localUser("konto-" + UUID.randomUUID() + "@stadt.example"));
  }

  @AfterEach
  void cleanUp() {
    providerRepository.findAll().stream()
        .filter(OidcProvider::isLocal)
        .forEach(providerRepository::delete);
    userRepository.findAll().stream()
        .filter(u -> LocalIssuer.URN.equals(u.getIssuer()))
        .forEach(userRepository::delete);
    settingsRepository
        .findSingleton()
        .filter(settings -> !settings.values().equals(LocalAuthSettings.Values.defaults()))
        .ifPresent(
            settings -> {
              settings.replace(LocalAuthSettings.Values.defaults(), null, Instant.now());
              settingsRepository.save(settings);
            });
  }

  @Test
  void storesAndReadsLocalCredentials() {
    LocalCredentials credentials = new LocalCredentials(user.getId(), "Sachbearbeitung", now);
    credentials.setPasswordHash(passwordEncoder.encode("korrekt-batterie-pferd-klammer"), now);
    credentials.markEmailVerified(now);
    credentials.markBootstrap();
    credentialsRepository.save(credentials);

    LocalCredentials reloaded = credentialsRepository.findById(user.getId()).orElseThrow();

    assertThat(reloaded.getCreatedReason()).isEqualTo("Sachbearbeitung");
    assertThat(
            passwordEncoder.matches("korrekt-batterie-pferd-klammer", reloaded.getPasswordHash()))
        .isTrue();
    assertThat(reloaded.state(now)).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(reloaded.isBootstrap()).isTrue();
    assertThat(reloaded.getVersion()).isZero();
    assertThat(credentialsRepository.findByBootstrapTrue()).contains(reloaded);
  }

  @Test
  void countsFailedLoginsAtomicallyInTheDatabase() {
    credentialsRepository.save(new LocalCredentials(user.getId(), "Test", now));

    assertThat(credentialsRepository.recordFailedLogin(user.getId(), now)).isEqualTo(1);
    assertThat(credentialsRepository.recordFailedLogin(user.getId(), now)).isEqualTo(1);
    assertThat(credentialsRepository.recordFailedLogin(UUID.randomUUID(), now)).isZero();

    LocalCredentials reloaded = credentialsRepository.findById(user.getId()).orElseThrow();
    assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(2);
    assertThat(reloaded.getUpdatedAt()).isEqualTo(now);
  }

  @Test
  void theSchemaRefusesASecondLocalAccountWithTheSameAddress() {
    assertThatThrownBy(() -> userRepository.saveAndFlush(localUser(user.getEmail().toUpperCase())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void storesTheLocalProviderRowWithoutAClientId() {
    OidcProvider saved = providerRepository.save(OidcProvider.localProvider("Lokale Konten"));

    OidcProvider reloaded = providerRepository.findById(saved.getId()).orElseThrow();

    assertThat(reloaded.getProviderType()).isEqualTo(ProviderType.LOCAL);
    assertThat(reloaded.getClientId()).isNull();
    assertThat(reloaded.isEnabled()).isFalse();
    assertThat(reloaded.isDefaultProvider()).isFalse();
    assertThatThrownBy(() -> providerRepository.saveAndFlush(OidcProvider.localProvider("Zweite")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void revokesAWholeFamilyAtomicallyAndCountsTheRows() {
    UUID familyA = UUID.randomUUID();
    UUID familyB = UUID.randomUUID();
    refreshTokenRepository.save(refreshToken(familyA, "a1"));
    refreshTokenRepository.save(refreshToken(familyA, "a2"));
    refreshTokenRepository.save(refreshToken(familyB, "b1"));

    int revoked =
        refreshTokenRepository.revokeFamily(familyA, RevocationReason.REUSE_DETECTED, now);

    assertThat(revoked).isEqualTo(2);
    assertThat(refreshTokenRepository.revokeFamily(familyA, RevocationReason.REUSE_DETECTED, now))
        .isZero();
    LocalRefreshToken untouched =
        refreshTokenRepository
            .findByTokenLookupHash(keyService.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, "b1"))
            .orElseThrow();
    assertThat(untouched.isActive(now)).isTrue();
    LocalRefreshToken revokedToken =
        refreshTokenRepository
            .findByTokenLookupHash(keyService.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, "a1"))
            .orElseThrow();
    assertThat(revokedToken.getRevocationReason()).isEqualTo(RevocationReason.REUSE_DETECTED);
    assertThat(revokedToken.isActive(now)).isFalse();

    assertThat(refreshTokenRepository.revokeAllForUser(user.getId(), RevocationReason.ADMIN, now))
        .isEqualTo(1);
    assertThat(refreshTokenRepository.deleteExpiredBefore(now.plus(Duration.ofDays(8))))
        .isEqualTo(3);
  }

  @Test
  void rotatesAnActiveTokenExactlyOnceUnderConcurrency() throws Exception {
    UUID family = UUID.randomUUID();
    LocalRefreshToken presented = refreshTokenRepository.save(refreshToken(family, "shared"));
    LocalRefreshToken successorA = refreshTokenRepository.save(refreshToken(family, "succ-a"));
    LocalRefreshToken successorB = refreshTokenRepository.save(refreshToken(family, "succ-b"));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> a = pool.submit(rotation(start, presented.getId(), successorA.getId()));
      Future<Integer> b = pool.submit(rotation(start, presented.getId(), successorB.getId()));
      start.countDown();

      int wins = a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS);

      assertThat(wins).as("exactly one concurrent rotation may win").isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    LocalRefreshToken reloaded = refreshTokenRepository.findById(presented.getId()).orElseThrow();
    assertThat(reloaded.getRevocationReason()).isEqualTo(RevocationReason.ROTATED);
    assertThat(reloaded.getRevokedAt()).isEqualTo(now);
    assertThat(reloaded.getRotatedToId()).isIn(successorA.getId(), successorB.getId());
    assertThat(
            refreshTokenRepository.rotateIfActive(
                presented.getId(), successorA.getId(), RevocationReason.ROTATED, now))
        .as("a rotated token is never rotated again - the caller treats it as reuse")
        .isZero();
  }

  @Test
  void doesNotRotateAnExpiredToken() {
    UUID family = UUID.randomUUID();
    LocalRefreshToken expired =
        refreshTokenRepository.save(
            new LocalRefreshToken(
                family,
                user.getId(),
                keyService.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, "expired"),
                now.minus(Duration.ofDays(2)),
                now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(30))));
    LocalRefreshToken successor = refreshTokenRepository.save(refreshToken(family, "succ"));

    assertThat(
            refreshTokenRepository.rotateIfActive(
                expired.getId(), successor.getId(), RevocationReason.ROTATED, now))
        .isZero();
    assertThat(refreshTokenRepository.findById(expired.getId()).orElseThrow().getRevokedAt())
        .isNull();
  }

  private Callable<Integer> rotation(CountDownLatch start, UUID presentedId, UUID successorId) {
    return () -> {
      start.await();
      return refreshTokenRepository.rotateIfActive(
          presentedId, successorId, RevocationReason.ROTATED, now);
    };
  }

  @Test
  void consumesAnActionTokenExactlyOnce() {
    LocalActionToken token =
        new LocalActionToken(
            user.getId(),
            ActionTokenPurpose.RESET_PASSWORD,
            keyService.lookupHash(Purpose.ACTION_TOKEN_LOOKUP, "raw"),
            now,
            now.plus(Duration.ofMinutes(30)));
    actionTokenRepository.save(token);

    assertThat(
            actionTokenRepository.findByTokenHashAndPurpose(
                keyService.lookupHash(Purpose.ACTION_TOKEN_LOOKUP, "raw"),
                ActionTokenPurpose.RESET_PASSWORD))
        .isPresent();
    assertThat(actionTokenRepository.markConsumed(token.getId(), now)).isEqualTo(1);
    assertThat(actionTokenRepository.markConsumed(token.getId(), now)).isZero();
    assertThat(actionTokenRepository.findById(token.getId()).orElseThrow().isRedeemable(now))
        .isFalse();
    assertThat(actionTokenRepository.deleteExpiredBefore(now.plus(Duration.ofDays(8))))
        .isEqualTo(1);
  }

  @Test
  void supersedesOpenTokensOfTheSamePurpose() {
    actionTokenRepository.save(
        new LocalActionToken(
            user.getId(),
            ActionTokenPurpose.SET_PASSWORD,
            "h1",
            now,
            now.plus(Duration.ofHours(72))));
    actionTokenRepository.save(
        new LocalActionToken(
            user.getId(),
            ActionTokenPurpose.RESET_PASSWORD,
            "h2",
            now,
            now.plus(Duration.ofHours(1))));

    assertThat(
            actionTokenRepository.consumeOpenTokens(
                user.getId(), ActionTokenPurpose.SET_PASSWORD, now))
        .isEqualTo(1);
    assertThat(
            actionTokenRepository.findByTokenHashAndPurpose(
                "h2", ActionTokenPurpose.RESET_PASSWORD))
        .get()
        .satisfies(token -> assertThat(token.isRedeemable(now)).isTrue());
  }

  @Test
  void keepsTheJtiDenylistUntilExpiry() {
    String jtiHash = LocalAuthKeyService.jtiHash(UUID.randomUUID().toString());
    revokedTokenRepository.save(
        new LocalRevokedToken(jtiHash, user.getId(), now.plus(Duration.ofMinutes(15)), now));

    assertThat(revokedTokenRepository.existsById(jtiHash)).isTrue();
    assertThat(revokedTokenRepository.deleteExpiredBefore(now)).isZero();
    assertThat(revokedTokenRepository.deleteExpiredBefore(now.plus(Duration.ofHours(1))))
        .isEqualTo(1);
    assertThat(revokedTokenRepository.existsById(jtiHash)).isFalse();
  }

  @Test
  void readsAndReplacesTheSettingsSingleton() {
    LocalAuthSettings settings = settingsRepository.findSingleton().orElseThrow();
    assertThat(settings.values()).isEqualTo(LocalAuthSettings.Values.defaults());
    assertThat(settings.getSelfRegistrationAllowedDomains()).isEmpty();
    // the row is shared by every test in this context, so only the increment is a fixed fact
    long versionBefore = settings.getVersion();

    settings.replace(
        new LocalAuthSettings.Values(
            true, List.of(" Stadt.Example ", "land.example"), false, 16, 24, 15, 30, 45),
        user.getId(),
        now);
    settingsRepository.save(settings);

    LocalAuthSettings reloaded = settingsRepository.findSingleton().orElseThrow();
    assertThat(reloaded.getSelfRegistrationAllowedDomains())
        .containsExactly("stadt.example", "land.example");
    assertThat(reloaded.isSelfRegistrationEnabled()).isTrue();
    assertThat(reloaded.isPasswordResetEnabled()).isFalse();
    assertThat(reloaded.getPasswordMinLength()).isEqualTo(16);
    assertThat(reloaded.getInvitationTokenTtlHours()).isEqualTo(24);
    assertThat(reloaded.getResetTokenTtlMinutes()).isEqualTo(15);
    assertThat(reloaded.getDefaultExpiryDays()).isEqualTo(30);
    assertThat(reloaded.getInactiveDays()).isEqualTo(45);
    assertThat(reloaded.getUpdatedBy()).isEqualTo(user.getId());
    assertThat(reloaded.getVersion()).isEqualTo(versionBefore + 1);
  }

  @Test
  void theSettingsRowRefusesWhatTheAdrForbids() {
    LocalAuthSettings settings = settingsRepository.findSingleton().orElseThrow();

    assertThatThrownBy(
            () ->
                settings.replace(
                    new LocalAuthSettings.Values(false, List.of(), true, 7, 72, 30, 90, 90),
                    null,
                    now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exposesTheCryptoBeansInTheDevContext() {
    assertThat(passwordEncoder.encode("x".repeat(12))).startsWith("{bcrypt}$2a$12$");
    assertThat(keyService.key(Purpose.ACCESS_TOKEN).getEncoded()).hasSize(32);
  }

  private static User localUser(String email) {
    UUID id = UUID.randomUUID();
    User user = new User(id.toString(), LocalIssuer.URN, email, "Lokales Konto");
    user.setOrganizationId(Organization.DEFAULT_ID);
    return user;
  }

  private LocalRefreshToken refreshToken(UUID familyId, String rawToken) {
    return new LocalRefreshToken(
        familyId,
        user.getId(),
        keyService.lookupHash(Purpose.REFRESH_TOKEN_LOOKUP, rawToken),
        now,
        now.plus(Duration.ofDays(7)),
        now.plus(Duration.ofDays(30)));
  }
}
