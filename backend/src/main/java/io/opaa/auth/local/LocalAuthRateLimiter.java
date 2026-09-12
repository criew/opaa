package io.opaa.auth.local;

import io.opaa.api.RateLimitProperties;
import io.opaa.api.RateLimitProperties.LocalAuthLimit;
import io.opaa.api.RateLimitService;
import io.opaa.api.RateLimitService.Decision;
import io.opaa.common.TooManyRequestsException;
import io.opaa.observability.RateLimitMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The local-auth limits that need more than the request path (ADR-0033, Entscheidung 9): the
 * password change per authenticated account, and registration and password reset per e-mail address
 * (#1538 calls {@link #requireAddressAllowance} before it does anything with the address). A
 * refusal is a {@link TooManyRequestsException} with the seconds to wait, which {@code
 * GlobalExceptionHandler} turns into {@code 429} with {@code Retry-After}. Addresses are keyed by a
 * hash of their lower-cased form and never held as text; with {@code opaa.rate-limit.enabled =
 * false} nothing is refused. Depends on {@code io.opaa.api}'s limiter and properties on purpose:
 * the rate-limit building blocks live there, and this class is the auth-side consumer of them, not
 * the other way round.
 */
@Component
public class LocalAuthRateLimiter {

  /** The endpoints with a per-address budget. */
  public enum AddressScope {
    REGISTER("local-auth-register"),
    FORGOT_PASSWORD("local-auth-forgot-password");

    private final String limitName;

    AddressScope(String limitName) {
      this.limitName = limitName;
    }
  }

  static final String CHANGE_PASSWORD_LIMIT = "local-auth-change-password";

  private final boolean enabled;
  private final RateLimitService changePassword;
  private final Map<AddressScope, RateLimitService> byAddress = new EnumMap<>(AddressScope.class);
  private final RateLimitMetrics metrics;

  public LocalAuthRateLimiter(RateLimitProperties properties, RateLimitMetrics metrics) {
    this.enabled = properties.enabled();
    this.metrics = metrics;
    LocalAuthLimit change = properties.localAuth().changePassword();
    this.changePassword = new RateLimitService(change.maxRequests(), change.windowSeconds());
    addressLimiter(AddressScope.REGISTER, properties.localAuth().register());
    addressLimiter(AddressScope.FORGOT_PASSWORD, properties.localAuth().forgotPassword());
  }

  private void addressLimiter(AddressScope scope, LocalAuthLimit limit) {
    if (limit.hasAddressLimit()) {
      byAddress.put(
          scope, new RateLimitService(limit.maxRequestsPerAddress(), limit.windowSeconds()));
    }
  }

  /** Counts one password-change attempt of {@code subject}; throws when its budget is spent. */
  public void requireChangePasswordAllowance(UUID subject) {
    if (!enabled) {
      return;
    }
    refuseIfSpent(changePassword.tryAcquire(subject.toString()), CHANGE_PASSWORD_LIMIT, "subject");
  }

  /**
   * Counts one request naming {@code email} in {@code scope}; throws when the address's budget is
   * spent. A blank address is not counted - it never reaches a mailbox.
   */
  public void requireAddressAllowance(AddressScope scope, String email) {
    RateLimitService limiter = byAddress.get(scope);
    if (!enabled || limiter == null || email == null || email.isBlank()) {
      return;
    }
    refuseIfSpent(limiter.tryAcquire(addressKey(email)), scope.limitName, "address");
  }

  private void refuseIfSpent(Decision decision, String limit, String scope) {
    if (decision.allowed()) {
      return;
    }
    metrics.recordRejected(limit, scope);
    throw new TooManyRequestsException(decision.retryAfterSeconds());
  }

  private static String addressKey(String email) {
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JVM", e);
    }
  }
}
