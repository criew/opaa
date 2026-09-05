package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.common.ValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/**
 * {@link OidcProviderRegistry} (#1329, ADR-0025 Entscheidung 1): the process-local registry of
 * enabled providers the multi-issuer resolver consults per request. Only enabled rows get a
 * decoder; a row whose address the SSRF policy rejects or whose decoder cannot be built is skipped
 * with a log line instead of blocking the others and retried after the interval; an unknown issuer
 * gets a manager that refuses with {@code unknown_issuer}; a refresh replaces the whole set.
 */
class OidcProviderRegistryTest {

  private static final Instant START = Instant.parse("2026-09-05T12:00:00Z");

  private final OidcProviderRepository repository = mock(OidcProviderRepository.class);
  private final OidcJwtDecoderFactory decoderFactory = mock(OidcJwtDecoderFactory.class);
  private final OidcAddressPolicy addressPolicy = mock(OidcAddressPolicy.class);
  private final JwtDecoder decoderA = mock(JwtDecoder.class);
  private final JwtDecoder decoderB = mock(JwtDecoder.class);
  private final AtomicReference<Instant> now = new AtomicReference<>(START);

  private OidcProvider enabledA;
  private OidcProvider enabledB;
  private OidcProviderRegistry registry;

  @BeforeEach
  void setUp() {
    enabledA = OidcProviderServiceTest.provider("A", "https://idp.example/realms/a", true, true);
    enabledB = OidcProviderServiceTest.provider("B", "https://idp.example/realms/b", true, false);
    when(repository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc())
        .thenReturn(List.of(enabledA, enabledB));
    when(decoderFactory.create(enabledA)).thenReturn(decoderA);
    when(decoderFactory.create(enabledB)).thenReturn(decoderB);
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    registry = new OidcProviderRegistry(repository, decoderFactory, addressPolicy, clock);
  }

  private static void assertUnknownIssuer(AuthenticationManager manager) {
    assertThatThrownBy(() -> manager.authenticate(new BearerTokenAuthenticationToken("x.y.z")))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(
            e ->
                assertThat(
                        ((BearerTokenError) ((OAuth2AuthenticationException) e).getError())
                            .getDescription())
                    .isEqualTo(OidcProviderRegistry.UNKNOWN_ISSUER));
  }

  @Test
  void resolvesAManagerForEveryEnabledIssuerAndRefusesAnUnknownOneAsUnknownIssuer() {
    registry.refresh();

    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/a")).contains(enabledA);
    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/b")).contains(enabledB);
    assertThat(registry.enabledProviders()).containsExactly(enabledA, enabledB);
    assertThat(registry.healthOf(enabledA.getId()).ready()).isTrue();
    assertUnknownIssuer(registry.resolve("https://idp.example/realms/unknown"));
  }

  @Test
  void loadsItselfOnFirstUseWithoutAnExplicitRefresh() {
    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/a")).isPresent();
    verify(repository).findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc();
  }

  @Test
  void aDisabledProviderDisappearsWithTheNextRefresh() {
    registry.refresh();
    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/b")).isPresent();

    when(repository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc())
        .thenReturn(List.of(enabledA));
    registry.onProvidersChanged(new OidcProvidersChangedEvent());

    assertUnknownIssuer(registry.resolve("https://idp.example/realms/b"));
    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/a")).isPresent();
  }

  @Test
  void aProviderWhoseDecoderCannotBeBuiltIsSkippedReportedAndRetriedAfterTheInterval() {
    when(decoderFactory.create(enabledA))
        .thenThrow(new IllegalStateException("Discovery-Dokument: nicht erreichbar"))
        .thenReturn(decoderA);

    registry.refresh();

    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/a")).isEmpty();
    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/b")).isPresent();
    OidcProviderRegistry.Health health = registry.healthOf(enabledA.getId());
    assertThat(health.ready()).isFalse();
    assertThat(health.message()).contains("nicht erreichbar");

    // before the interval: no second attempt, still refused
    assertUnknownIssuer(registry.resolve("https://idp.example/realms/a"));
    verify(decoderFactory, times(1)).create(enabledA);

    now.set(START.plus(OidcProviderRegistry.RETRY_INTERVAL).plus(Duration.ofSeconds(1)));
    assertThat(registry.resolve("https://idp.example/realms/a")).isNotNull();
    verify(decoderFactory, times(2)).create(enabledA);
    assertThat(registry.healthOf(enabledA.getId()).ready()).isTrue();
    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/a")).contains(enabledA);
  }

  @Test
  void aProviderWhoseAddressTheSsrfPolicyRejectsIsSkippedBeforeAnyDecoderIsBuilt() {
    // a row edited directly in the database, or an allowlist narrowed after the row was saved
    doThrow(new ValidationException("gesperrt"))
        .when(addressPolicy)
        .requireAllowed("https://idp.example/realms/a", "Issuer-URI");

    registry.refresh();

    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/a")).isEmpty();
    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/b")).isPresent();
    verify(decoderFactory, never()).create(enabledA);
    verify(decoderFactory).create(enabledB);
    assertThat(registry.healthOf(enabledA.getId()).message()).contains("gesperrt");
  }

  @Test
  void aTrailingSlashOnTheTokensIssuerStillFindsTheProvider() {
    registry.refresh();

    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/a/")).contains(enabledA);
    verify(decoderFactory, times(2)).create(any());
  }

  /**
   * The retry interval must hold under concurrency: request threads that pass the "is a retry due?"
   * check together may not each rebuild a provider that stays broken - every attempt is a discovery
   * fetch with a 15 s budget on a request thread, serialized behind the registry's lock.
   */
  @Test
  void concurrentRetriesOfAProviderThatStaysBrokenAttemptTheRebuildOnlyOnce() throws Exception {
    when(decoderFactory.create(enabledA))
        .thenThrow(new IllegalStateException("nicht erreichbar"))
        .thenAnswer(
            invocation -> {
              Thread.sleep(300);
              throw new IllegalStateException("immer noch nicht erreichbar");
            });
    registry.refresh();
    now.set(START.plus(OidcProviderRegistry.RETRY_INTERVAL).plus(Duration.ofSeconds(1)));

    CountDownLatch bothArrived = new CountDownLatch(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<AuthenticationManager>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(
            pool.submit(
                () -> {
                  bothArrived.countDown();
                  bothArrived.await();
                  return registry.resolve("https://idp.example/realms/a");
                }));
      }
      for (Future<AuthenticationManager> result : results) {
        assertThat(result.get(5, TimeUnit.SECONDS)).isNotNull();
      }
    } finally {
      pool.shutdownNow();
    }

    // one attempt in refresh, one retry - not one retry per thread
    verify(decoderFactory, times(2)).create(enabledA);
    assertThat(registry.healthOf(enabledA.getId()).message()).contains("immer noch");
  }

  @Test
  void aRefreshReusesTheDecodersOfUnchangedProvidersAndRebuildsOnlyChangedOnes() {
    registry.refresh();

    // a reorder: the same rows in another order - no decoder is rebuilt, the order is taken over
    when(repository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc())
        .thenReturn(List.of(enabledB, enabledA));
    registry.onProvidersChanged(new OidcProvidersChangedEvent());
    verify(decoderFactory, times(1)).create(enabledA);
    verify(decoderFactory, times(1)).create(enabledB);
    assertThat(registry.enabledProviders()).containsExactly(enabledB, enabledA);

    // a changed client id is a changed validator: that provider is rebuilt, the other is not
    OidcProvider aWithNewClient =
        OidcProviderServiceTest.provider("A", "https://idp.example/realms/a", true, true);
    aWithNewClient.replaceDetails(
        "A",
        "https://idp.example/realms/a",
        "other-client",
        null,
        OidcClaimMapping.keycloakDefaults());
    when(decoderFactory.create(aWithNewClient)).thenReturn(decoderA);
    when(repository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc())
        .thenReturn(List.of(enabledB, aWithNewClient));
    registry.onProvidersChanged(new OidcProvidersChangedEvent());
    verify(decoderFactory).create(aWithNewClient);
    verify(decoderFactory, times(1)).create(enabledB);
    assertThat(registry.findEnabledByIssuer("https://idp.example/realms/a"))
        .contains(aWithNewClient);
  }
}
