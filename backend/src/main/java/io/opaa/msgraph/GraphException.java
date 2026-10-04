package io.opaa.msgraph;

/**
 * A Graph call that did not succeed, by the kind a caller acts on. The message is German and
 * carries neither token, nor response body, nor a download address; no cause is attached.
 */
public final class GraphException extends Exception {

  /** What went wrong, as far as the caller needs to tell. */
  public enum Kind {
    /** {@code 401}: the access token was refused, also after a renewal. */
    UNAUTHORIZED,
    /** {@code 403}: the application may not read this. */
    FORBIDDEN,
    /** {@code 404}: missing or not visible to the application. */
    NOT_FOUND,
    /** {@code 410}: the delta token is no longer valid; enumerate anew. */
    RESYNC,
    /** The download exceeded the bound handed over. */
    TOO_LARGE,
    /** A target refused by the address validation, a redirect or page link not followed. */
    BLOCKED,
    /** Throttled beyond the retries, a server error, a broken connection, an odd answer. */
    TRANSIENT
  }

  private final Kind kind;
  private final int status;
  private final String errorCode;

  GraphException(Kind kind, int status, String errorCode, String message) {
    super(message);
    this.kind = kind;
    this.status = status;
    this.errorCode = errorCode;
  }

  public Kind kind() {
    return kind;
  }

  /** The HTTP status of the failing answer, {@code 0} when there was none. */
  public int status() {
    return status;
  }

  /** Graph's {@code error.code}, {@code null} when the answer carried none. */
  public String errorCode() {
    return errorCode;
  }
}
