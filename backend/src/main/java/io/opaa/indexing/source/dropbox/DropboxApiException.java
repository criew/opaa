package io.opaa.indexing.source.dropbox;

/**
 * A Dropbox API call that did not succeed, by the kind the connector acts on. The message is German
 * and carries neither token nor response body; {@link #summary()} is Dropbox's {@code
 * error_summary}, a path-free error tag such as {@code path/not_found/..}.
 */
final class DropboxApiException extends Exception {

  enum Kind {
    /** {@code 401}: the access token was refused. */
    UNAUTHORIZED,
    /** {@code 401 missing_scope}: the app lacks a permission; no renewal helps. */
    SCOPE_MISSING,
    /** {@code 409 .../not_found}: the path or id does not exist. */
    NOT_FOUND,
    /** {@code 409 reset}: the cursor is no longer accepted. */
    RESET,
    /** Any other endpoint error ({@code 409}): restricted content, not a file, not a folder. */
    CONFLICT,
    /** {@code 403}: the account may not do this (a team space without access). */
    FORBIDDEN,
    /** The download exceeded the bound handed over. */
    TOO_LARGE,
    /** The target address is blocked by the operator's validation. */
    BLOCKED,
    /** {@code 400}: a request Dropbox does not take, always the same way. */
    BAD_REQUEST,
    /** Throttled beyond the retries, a server error, a broken connection, an odd answer. */
    TRANSIENT
  }

  private final Kind kind;
  private final int status;
  private final String summary;

  DropboxApiException(Kind kind, int status, String summary, String message) {
    super(message);
    this.kind = kind;
    this.status = status;
    this.summary = summary == null ? "" : summary;
  }

  Kind kind() {
    return kind;
  }

  int status() {
    return status;
  }

  String summary() {
    return summary;
  }

  /** Whether Dropbox's error tag starts with {@code tag}, such as {@code path/not_file}. */
  boolean is(String tag) {
    return summary.startsWith(tag);
  }
}
