package io.opaa.auth.oidc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The process-local registry of enabled providers the multi-issuer resolver consults for every
 * bearer token (#1329, ADR-0025 Entscheidung 1): one {@link JwtDecoder} per enabled row, keyed by
 * the normalized issuer. An issuer no enabled provider owns resolves to a manager that refuses
 * every token with {@code error_description="unknown_issuer"} - the SPA tells that apart from an
 * expired token - so a provider disabled or deleted is refused from the first token after the
 * commit.
 *
 * <p>Rebuilt as a whole on every {@link OidcProvidersChangedEvent} <em>after</em> its transaction
 * committed (mirroring {@code ActiveChatModelResolver}), and lazily on first use. A row whose
 * address the {@link OidcAddressPolicy} rejects or whose decoder cannot be built (discovery not
 * reachable yet - Keycloak regularly starts after OPAA in the Compose stack) is logged and skipped
 * so the remaining providers stay reachable; it is kept as {@link Failure} and rebuilt on the next
 * token of its issuer once {@link #RETRY_INTERVAL} passed, so it does not stay out until the next
 * restart. Process-local without distributed invalidation, per ADR-0021.
 */
public class OidcProviderRegistry implements AuthenticationManagerResolver<String> {

  private static final Logger log = LoggerFactory.getLogger(OidcProviderRegistry.class);

  static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);
  static final String UNKNOWN_ISSUER = "unknown_issuer";

  private record Entry(OidcProvider provider, AuthenticationManager authenticationManager) {}

  private record Failure(OidcProvider provider, String message, Instant lastAttempt) {}

  /** Whether a provider's decoder is ready, or why not - for the admin UI (#1333). */
  public record Health(boolean ready, String message) {}

  private final OidcProviderRepository repository;
  private final OidcJwtDecoderFactory decoderFactory;
  private final OidcAddressPolicy addressPolicy;
  private final Clock clock;
  private final Object lock = new Object();
  private volatile Map<String, Entry> entries;
  private volatile Map<String, Failure> failures = Map.of();

  public OidcProviderRegistry(
      OidcProviderRepository repository,
      OidcJwtDecoderFactory decoderFactory,
      OidcAddressPolicy addressPolicy,
      Clock clock) {
    this.repository = repository;
    this.decoderFactory = decoderFactory;
    this.addressPolicy = addressPolicy;
    this.clock = clock;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProvidersChanged(OidcProvidersChangedEvent event) {
    refresh();
  }

  /** Rebuilds the whole set from the enabled rows; never throws for a single bad row. */
  @Transactional(readOnly = true)
  public void refresh() {
    synchronized (lock) {
      Map<String, Entry> built = new LinkedHashMap<>();
      Map<String, Failure> failed = new LinkedHashMap<>();
      for (OidcProvider provider :
          repository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc()) {
        String issuer = OidcIssuerUris.normalize(provider.getIssuerUri());
        try {
          built.put(issuer, build(provider));
        } catch (RuntimeException e) {
          failed.put(issuer, new Failure(provider, e.getMessage(), clock.instant()));
          log.warn(
              "Skipping OIDC provider '{}' ({}): {}",
              provider.getDisplayName(),
              provider.getIssuerUri(),
              e.getMessage());
        }
      }
      // insertion order is the sign-in page order - Map.copyOf would lose it
      entries = Collections.unmodifiableMap(built);
      failures = Collections.unmodifiableMap(failed);
      log.info(
          "OIDC provider registry rebuilt: {} ready, {} unavailable", built.size(), failed.size());
    }
  }

  /**
   * Never {@code null}: an unknown issuer gets a manager that refuses with {@code unknown_issuer}.
   */
  @Override
  public AuthenticationManager resolve(String issuer) {
    String normalized = OidcIssuerUris.normalize(issuer);
    Entry entry = loaded().get(normalized);
    if (entry == null) {
      entry = retryIfDue(normalized);
    }
    if (entry != null) {
      return entry.authenticationManager();
    }
    return authentication -> {
      throw new OAuth2AuthenticationException(
          new BearerTokenError(
              BearerTokenErrors.invalidToken(UNKNOWN_ISSUER).getErrorCode(),
              org.springframework.http.HttpStatus.UNAUTHORIZED,
              UNKNOWN_ISSUER,
              null));
    };
  }

  public Optional<OidcProvider> findEnabledByIssuer(String issuer) {
    return Optional.ofNullable(loaded().get(OidcIssuerUris.normalize(issuer))).map(Entry::provider);
  }

  /** The enabled providers whose decoder is ready, in sign-in page order. */
  public List<OidcProvider> enabledProviders() {
    return loaded().values().stream().map(Entry::provider).toList();
  }

  /** {@link Health} of one provider - {@code ready} when its decoder was built. */
  public Health healthOf(UUID providerId) {
    Map<String, Entry> ready = loaded();
    for (Entry entry : ready.values()) {
      if (entry.provider().getId().equals(providerId)) {
        return new Health(true, null);
      }
    }
    for (Failure failure : failures.values()) {
      if (failure.provider().getId().equals(providerId)) {
        return new Health(false, failure.message());
      }
    }
    return new Health(false, null);
  }

  private Entry build(OidcProvider provider) {
    addressPolicy.requireAllowed(provider.getIssuerUri(), "Issuer-URI");
    if (provider.getJwkSetUri() != null) {
      addressPolicy.requireAllowed(provider.getJwkSetUri(), "JWK-Set-URI");
    }
    JwtDecoder decoder = decoderFactory.create(provider);
    JwtAuthenticationProvider authenticationProvider = new JwtAuthenticationProvider(decoder);
    return new Entry(provider, authenticationProvider::authenticate);
  }

  /** A second attempt for a failed provider, at most once per {@link #RETRY_INTERVAL}. */
  private Entry retryIfDue(String issuer) {
    Failure failure = failures.get(issuer);
    if (failure == null
        || Duration.between(failure.lastAttempt(), clock.instant()).compareTo(RETRY_INTERVAL) < 0) {
      return null;
    }
    synchronized (lock) {
      Entry already = entries.get(issuer);
      if (already != null) {
        return already;
      }
      try {
        Entry entry = build(failure.provider());
        Map<String, Entry> ready = new LinkedHashMap<>(entries);
        ready.put(issuer, entry);
        entries = Collections.unmodifiableMap(ready);
        Map<String, Failure> remaining = new LinkedHashMap<>(failures);
        remaining.remove(issuer);
        failures = Collections.unmodifiableMap(remaining);
        log.info("OIDC provider '{}' became available", failure.provider().getDisplayName());
        return entry;
      } catch (RuntimeException e) {
        Map<String, Failure> updated = new LinkedHashMap<>(failures);
        updated.put(issuer, new Failure(failure.provider(), e.getMessage(), clock.instant()));
        failures = Collections.unmodifiableMap(updated);
        log.warn(
            "OIDC provider '{}' still unavailable: {}",
            failure.provider().getDisplayName(),
            e.getMessage());
        return null;
      }
    }
  }

  private Map<String, Entry> loaded() {
    Map<String, Entry> current = entries;
    if (current == null) {
      refresh();
      current = entries;
    }
    return current;
  }
}
