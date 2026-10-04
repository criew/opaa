package io.opaa.indexing.source;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The secret of one run as a connector asks for it before every request or sign-in: the core's
 * answer is reused for at most the validity, then asked again, so a secret discarded or a source
 * blocked meanwhile ends the run at its next access without decrypting per document. A refusal
 * sticks: every later ask of the run throws the same {@link SourceConnectionBlockedException}. Safe
 * for concurrent downloads of one run.
 */
public final class RunCredentials {

  /**
   * How long one answer of the core is reused. An ask reads and decrypts the stored secret and
   * evaluates the source's blocks, a handful of queries; ten seconds bound that to six asks a
   * minute however fast a run fetches, and a discard still takes effect within seconds.
   */
  public static final Duration VALIDITY = Duration.ofSeconds(10);

  private final Supplier<Secret> core;
  private final long validityNanos;
  private final LongSupplier ticker;
  private boolean asked;
  private long askedAt;
  private Secret secret;
  private SourceConnectionBlockedException refusal;

  /** Asks {@code core} at most once per {@link #VALIDITY}. */
  public RunCredentials(Supplier<Secret> core) {
    this(core, VALIDITY, System::nanoTime);
  }

  /** Asks {@code core} at most once per {@code validity}, measured on {@code ticker} (nanos). */
  public RunCredentials(Supplier<Secret> core, Duration validity, LongSupplier ticker) {
    this.core = Objects.requireNonNull(core, "core");
    this.validityNanos = validity.toNanos();
    this.ticker = ticker;
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
    long now = ticker.getAsLong();
    if (!asked || now - askedAt >= validityNanos) {
      try {
        secret = core.get();
      } catch (SourceConnectionBlockedException e) {
        refusal = e;
        throw e;
      }
      asked = true;
      askedAt = now;
    }
    return secret;
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
