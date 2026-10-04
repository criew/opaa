package io.opaa.indexing.source;

import java.util.function.Supplier;

/**
 * What a connector sends derived from the run's secret - a header, parsed credentials - answered
 * anew before every request ({@link #get}). After the source rejected what one request sent, {@link
 * #renewedAfterRejection} tells whether another one is held now, worth exactly one retry of that
 * request; otherwise the connector reports the rejection.
 */
public interface RenewableCredential<T> extends Supplier<T> {

  /**
   * Whether, after the source rejected {@code sent}, a different value is held now.
   *
   * @throws SourceConnectionBlockedException when the run may not reach its source any more
   */
  boolean renewedAfterRejection(T sent);

  /** {@code value} for every request, never renewed - a connection test's fixed secret. */
  static <T> RenewableCredential<T> fixed(T value) {
    return new RenewableCredential<>() {
      @Override
      public T get() {
        return value;
      }

      @Override
      public boolean renewedAfterRejection(T sent) {
        return false;
      }
    };
  }
}
