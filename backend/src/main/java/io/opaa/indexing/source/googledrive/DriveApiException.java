package io.opaa.indexing.source.googledrive;

/**
 * A Drive API call that did not succeed, by the kind the connector acts on. The message is German
 * and carries neither token nor response body.
 */
final class DriveApiException extends Exception {

  enum Kind {
    /** {@code 401}: the access token was refused. */
    UNAUTHORIZED,
    /** {@code 404}: missing or not visible - Drive does not tell the two apart. */
    NOT_FOUND,
    /** {@code 403} without a more specific reason. */
    FORBIDDEN,
    /** {@code 403 insufficientPermissions}: the token lacks the Drive scope. */
    SCOPE_MISSING,
    /** {@code 403 dailyLimitExceeded}: no request of this run will do better. */
    DAILY_LIMIT,
    /** The export of a Google file exceeds Drive's limit. */
    EXPORT_LIMIT,
    /** The download exceeded the bound handed over. */
    TOO_LARGE,
    /** The target address is blocked by the operator's validation. */
    BLOCKED,
    /** Throttled beyond the retries, a server error, a broken connection, an odd answer. */
    TRANSIENT
  }

  private final Kind kind;
  private final int status;
  private String reason;

  DriveApiException(Kind kind, int status, String message) {
    super(message);
    this.kind = kind;
    this.status = status;
  }

  Kind kind() {
    return kind;
  }

  int status() {
    return status;
  }

  /** The first {@code errors[].reason} of Google's answer, {@code null} for none. */
  String reason() {
    return reason;
  }

  DriveApiException withReason(String reason) {
    this.reason = reason;
    return this;
  }
}
