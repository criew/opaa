package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.opaa.account.LocalRefreshTokenService.IssuedRefreshToken;
import io.opaa.account.LocalRefreshTokenService.RotationResult;
import io.opaa.account.web.LocalAuthController;
import io.opaa.api.dto.LocalChangePasswordRequest;
import io.opaa.api.dto.LocalLoginRequest;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.auth.UserRepository;
import io.opaa.auth.UserService;
import io.opaa.common.UnauthorizedException;
import io.opaa.security.ClientIpResolver;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An act that ends every session of an account, committed at the worst moment of a sign-in or a
 * renewal, leaves that sign-in or renewal nothing usable: no active refresh token and no access
 * token. Each interleaving is fixed - the act runs inside a hook between the two steps it races, or
 * waits on the renewal's transaction before that one commits - with no timing assumption.
 */
@OpaaLocalAuthMockMvcTest
class LocalSessionEndRaceIntegrationTest {

  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private LocalLoginService loginService;
  @Autowired private LocalAccessTokenService accessTokens;
  @Autowired private LocalRefreshTokenService refreshTokens;
  @Autowired private LocalRefreshTokenRepository refreshRepository;
  @Autowired private LocalTokenRevocationService revocation;
  @Autowired private LocalPasswordService passwords;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private UserService userService;
  @Autowired private UserRepository users;
  @Autowired private LocalRefreshCookies cookies;
  @Autowired private ClientIpResolver clientIpResolver;
  @Autowired private LocalAuthRateLimiter rateLimiter;
  @Autowired private OidcProviderRegistry registry;
  @Autowired private LocalAuthKeyService keys;
  @Autowired private LocalAuthProperties properties;
  @Autowired private AuditEventRecorder audit;
  @Autowired private LocalAdminNetworkPolicy networkPolicy;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private Clock clock;

  private LocalAccountFixtures fixtures;
  private LocalAccount account;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    account = fixtures.activeUser("rennen-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    fixtures.cleanUp();
  }

  @Test
  void aSignInWhoseSessionsEndAfterThePasswordCheckGetsNoRefreshToken() {
    LocalLoginService racing =
        mock(LocalLoginService.class, AdditionalAnswers.delegatesTo(loginService));
    doAnswer(
            invocation -> {
              Object authenticated =
                  loginService.authenticate(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2));
              endAllSessions();
              return authenticated;
            })
        .when(racing)
        .authenticate(anyString(), anyString(), any());
    LocalLoginRequest request = new LocalLoginRequest();
    request.setEmail(account.email());
    request.setPassword(LocalAccountFixtures.PASSWORD);

    Throwable refused =
        catchThrowable(
            () -> controller(racing, refreshTokens).login(request, new MockHttpServletRequest()));

    assertThat(activeRefreshTokens()).isZero();
    assertThat(refused).isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void aRenewalWhoseSessionsEndRightAfterTheRotationMintsNoAccessToken() {
    IssuedRefreshToken presented = refreshTokens.issue(account.user());
    LocalRefreshTokenService racing =
        mock(LocalRefreshTokenService.class, AdditionalAnswers.delegatesTo(refreshTokens));
    doAnswer(
            invocation -> {
              Object rotated =
                  refreshTokens.rotate(invocation.getArgument(0), invocation.getArgument(1));
              endAllSessions();
              return rotated;
            })
        .when(racing)
        .rotate(anyString(), any());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(LocalRefreshCookies.COOKIE_NAME, presented.value()));

    ResponseEntity<Object> answer = controller(loginService, racing).refresh(request);

    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aPasswordChangeWhoseSessionsEndBeforeTheNewSessionOpensIsUnauthorized() {
    LocalPasswordService racing =
        mock(LocalPasswordService.class, AdditionalAnswers.delegatesTo(passwords));
    doAnswer(
            invocation -> {
              Object changed =
                  passwords.changePassword(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2));
              endAllSessions();
              return changed;
            })
        .when(racing)
        .changePassword(any(), anyString(), anyString());
    LocalChangePasswordRequest request = new LocalChangePasswordRequest();
    request.setCurrentPassword(LocalAccountFixtures.PASSWORD);
    request.setNewPassword("Ein-ganz-neues-Passwort!2026");
    CurrentUser caller =
        CurrentUser.of(account.id(), account.user().getOrganizationId(), SystemRole.USER, "Konto");

    Throwable refused =
        catchThrowable(
            () -> controller(loginService, racing, refreshTokens).changePassword(caller, request));

    assertThat(refused).isInstanceOf(UnauthorizedException.class);
    assertThat(activeRefreshTokens()).isZero();
  }

  @Test
  void anEndOfAllSessionsThatWaitsOnARotationAlsoRevokesItsSuccessor() throws Exception {
    IssuedRefreshToken presented = refreshTokens.issue(account.user());
    AtomicReference<Thread> ending = new AtomicReference<>();
    // delegates every call to the real repository; the rotation's write is followed by the act
    LocalRefreshTokenRepository racing =
        (LocalRefreshTokenRepository)
            Proxy.newProxyInstance(
                LocalRefreshTokenRepository.class.getClassLoader(),
                new Class<?>[] {LocalRefreshTokenRepository.class},
                (proxy, method, args) -> {
                  Object result = invoke(method, args);
                  if (method.getName().equals("rotateIfActive")) {
                    // still inside the rotation's transaction: the act starts and must wait
                    Integer rotationPid =
                        jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    ending.set(Thread.ofPlatform().start(this::endAllSessions));
                    await().atMost(Duration.ofSeconds(20)).until(() -> waitsBehind(rotationPid));
                  }
                  return result;
                });

    RotationResult result = rotationServiceOver(racing).rotate(presented.value(), null);
    ending.get().join(Duration.ofSeconds(30));

    assertThat(result).isInstanceOf(RotationResult.Rotated.class);
    UUID successor = ((RotationResult.Rotated) result).token().id();
    assertThat(
            jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM local_refresh_tokens WHERE id = ?",
                Boolean.class,
                successor))
        .isTrue();
    assertThat(activeRefreshTokens()).isZero();
  }

  @Test
  void aReplayThatWaitsOnARotationOfItsFamilyAlsoRevokesTheSuccessor() throws Exception {
    IssuedRefreshToken first = refreshTokens.issue(account.user());
    RotationResult.Rotated used =
        (RotationResult.Rotated) refreshTokens.rotate(first.value(), null);
    // the first rotation lies beyond the grace period, so presenting the token again is a replay
    jdbc.update(
        "UPDATE local_refresh_tokens SET revoked_at = revoked_at - interval '1 hour' WHERE id = ?",
        first.id());
    long generationBefore = sessionGeneration();
    AtomicReference<Thread> replaying = new AtomicReference<>();
    AtomicReference<RotationResult> replay = new AtomicReference<>();
    // delegates every call to the real repository; the rotation's write is followed by the replay
    LocalRefreshTokenRepository racing =
        (LocalRefreshTokenRepository)
            Proxy.newProxyInstance(
                LocalRefreshTokenRepository.class.getClassLoader(),
                new Class<?>[] {LocalRefreshTokenRepository.class},
                (proxy, method, args) -> {
                  Object result = invoke(method, args);
                  if (method.getName().equals("rotateIfActive")) {
                    Integer rotationPid =
                        jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    replaying.set(
                        Thread.ofPlatform()
                            .start(() -> replay.set(refreshTokens.rotate(first.value(), null))));
                    await().atMost(Duration.ofSeconds(20)).until(() -> waitsBehind(rotationPid));
                  }
                  return result;
                });

    RotationResult result = rotationServiceOver(racing).rotate(used.token().value(), null);
    replaying.get().join(Duration.ofSeconds(30));

    assertThat(result).isInstanceOf(RotationResult.Rotated.class);
    assertThat(replay.get()).isEqualTo(new RotationResult.Reused(account.id()));
    UUID successor = ((RotationResult.Rotated) result).token().id();
    assertThat(
            jdbc.queryForObject(
                "SELECT revocation_reason FROM local_refresh_tokens WHERE id = ?",
                String.class,
                successor))
        .isEqualTo(RevocationReason.REUSE_DETECTED.name());
    assertThat(activeRefreshTokens()).isZero();
    assertThat(sessionGeneration()).isGreaterThan(generationBefore);
  }

  private long sessionGeneration() {
    Long generation =
        jdbc.queryForObject(
            "SELECT session_generation FROM local_credentials WHERE user_id = ?",
            Long.class,
            account.id());
    return generation == null ? 0 : generation;
  }

  private Object invoke(Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(refreshRepository, args);
    } catch (InvocationTargetException failed) {
      throw failed.getCause();
    }
  }

  /** What every act that ends all sessions does: the generation first, then the refresh tokens. */
  private void endAllSessions() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              revocation.endAllSessions(account.id());
              refreshRepository.revokeAllForUser(
                  account.id(), RevocationReason.ADMIN, Instant.now());
            });
  }

  private boolean waitsBehind(int pid) {
    Integer waiting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
            Integer.class,
            pid);
    return waiting != null && waiting > 0;
  }

  private long activeRefreshTokens() {
    Long active =
        jdbc.queryForObject(
            "SELECT count(*) FROM local_refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Long.class,
            account.id());
    return active == null ? 0 : active;
  }

  private LocalAuthController controller(
      LocalLoginService login, LocalRefreshTokenService refresh) {
    return controller(login, passwords, refresh);
  }

  private LocalAuthController controller(
      LocalLoginService login, LocalPasswordService password, LocalRefreshTokenService refresh) {
    return new LocalAuthController(
        login,
        accessTokens,
        refresh,
        revocation,
        password,
        credentials,
        userService,
        cookies,
        clientIpResolver,
        rateLimiter,
        clock);
  }

  private LocalRefreshTokenService rotationServiceOver(LocalRefreshTokenRepository repository) {
    return new LocalRefreshTokenService(
        repository,
        users,
        credentials,
        registry,
        keys,
        properties,
        revocation,
        audit,
        networkPolicy,
        transactionManager,
        events,
        clock);
  }
}
