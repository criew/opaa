package io.opaa.indexing.source;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The secret of one run as a connector asks for it before every request or sign-in: the core's
 * answer is reused for at most the validity - never past {@link #EXPIRY_MARGIN} before the expiry
 * it carries - so a secret discarded or a source blocked meanwhile ends the run at its next access
 * without decrypting per document. A refusal sticks: every later ask of the run throws the same
 * {@link SourceConnectionBlockedException}. Safe for concurrent downloads of one run.
 */
public final class RunCredentials {

  /**
   * How long one answer of the core is reused. An ask reads and decrypts the stored secret and
   * evaluates the source's blocks, a handful of queries; ten seconds bound that to six asks a
   * minute however fast a run fetches, and a discard still takes effect within seconds.
   */
  public static final Duration VALIDITY = Duration.ofSeconds(10);

  /** How close to its own expiry an answer is no longer reused, so no request sends it late. */
  public static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

  private final Supplier<Secret> core;
  private final Duration validity;
  private final Clock clock;
  private Instant reuseUntil;
  private Secret secret;
  private SourceConnectionBlockedException refusal;

  /** Asks {@code core} at most once per {@code validity}, measured on {@code clock}. */
  public RunCredentials(Supplier<Secret> core, Duration validity, Clock clock) {
    this.core = Objects.requireNonNull(core, "core");
    this.validity = Objects.requireNonNull(validity, "validity");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The secret valid now, with its kind; {@code null} for none.
   *
   * @throws SourceConnectionBlockedException when the run may not reach its source any more
   */
  public synchronized Secret secret() {
    if (refusal != null) {
      throw refusal;
    }
    Instant now = clock.instant();
    if (reuseUntil == null || !now.isBefore(reuseUntil)) {
      try {
        secret = core.get();
      } catch (SourceConnectionBlockedException e) {
        refusal = e;
        throw e;
      }
      reuseUntil = reusableUntil(now, secret);
    }
    return secret;
  }

  private Instant reusableUntil(Instant now, Secret answer) {
    Instant byValidity = now.plus(validity);
    if (answer == null || answer.expiresAt() == null) {
      return byValidity;
    }
    Instant byExpiry = answer.expiresAt().minus(EXPIRY_MARGIN);
    return byExpiry.isBefore(byValidity) ? byExpiry : byValidity;
  }

  /** The value of {@link #secret()}, {@code null} for none. */
  public String value() {
    return Secret.valueOf(secret());
  }

  /**
   * Asks without using the answer, before an access whose client holds the secret itself.
   *
   * @throws SourceConnectionBlockedException when the run may not reach its source any more
   */
  public void check() {
    secret();
  }

  /**
   * Drops the answer held, so the next ask goes to the core - for a connector whose source refused
   * the secret ({@code 401}); a refusal of the core stays.
   */
  public synchronized void invalidate() {
    reuseUntil = null;
  }

  /**
   * {@code derive} of the value valid now ({@code null} for none) on every {@code get}, computed
   * again only when the value changed - for a client that needs a header or parsed credentials.
   * {@code get} throws what {@link #secret()} throws.
   */
  public <T> Supplier<T> derived(Function<String, T> derive) {
    Objects.requireNonNull(derive, "derive");
    return new Supplier<>() {
      private boolean computed;
      private String from;
      private T result;

      @Override
      public synchronized T get() {
        String current = value();
        if (!computed || !Objects.equals(current, from)) {
          result = derive.apply(current);
          from = current;
          computed = true;
        }
        return result;
      }
    };
  }
}
