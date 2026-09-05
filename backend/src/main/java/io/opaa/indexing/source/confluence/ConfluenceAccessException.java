package io.opaa.indexing.source.confluence;

import java.io.IOException;

/**
 * Base of every failure the access layer reports. {@link #getMessage()} is a German, user-facing
 * sentence fit for a run event or the connection test - it names the resource and the HTTP status,
 * never a header, a token or a raw upstream body, and no subclass or cause attached here may carry
 * one either (guarded by {@code ConfluenceClientContractTest#carriesNoCredentials} for the
 * exception chain and {@code ConfluenceLogLeakTest} for log output).
 */
public class ConfluenceAccessException extends IOException {

  public ConfluenceAccessException(String message) {
    super(message);
  }

  public ConfluenceAccessException(String message, Throwable cause) {
    super(message, cause);
  }

  /** {@code 401}: the instance rejected the credentials. */
  public static final class Authentication extends ConfluenceAccessException {
    public Authentication(String message) {
      super(message);
    }
  }

  /** {@code 403} on a single resource: the token is valid but may not read this space or page. */
  public static final class Forbidden extends ConfluenceAccessException {
    public Forbidden(String message) {
      super(message);
    }
  }

  /** {@code 404} where the caller did not expect a missing resource to be a regular outcome. */
  public static final class NotFound extends ConfluenceAccessException {
    public NotFound(String message) {
      super(message);
    }
  }

  /** {@code 429} kept coming after every retry the configuration allows. */
  public static final class RateLimited extends ConfluenceAccessException {
    public RateLimited(String message) {
      super(message);
    }
  }

  /**
   * The run's request budget ({@code ConfluenceProperties#requestBudgetPerRun}) is spent - not a
   * failure of the instance or the credentials but the run's own bound; the executor ends the run
   * in an orderly way as incomplete and the next run continues.
   */
  public static final class BudgetExhausted extends ConfluenceAccessException {
    private final int budget;

    public BudgetExhausted(int budget) {
      super(
          "Anfragebudget von "
              + budget
              + " Anfragen für diesen Lauf erschöpft; der nächste Lauf setzt fort");
      this.budget = budget;
    }

    public int budget() {
      return budget;
    }
  }

  /** The address answers, but not like the edition this connection was configured for. */
  public static final class EditionMismatch extends ConfluenceAccessException {
    public EditionMismatch(String message) {
      super(message);
    }
  }

  /** The address does not answer like any Confluence edition. */
  public static final class NoConfluence extends ConfluenceAccessException {
    public NoConfluence(String message) {
      super(message);
    }
  }
}
