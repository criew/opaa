package io.opaa.indexing.source.smb;

/**
 * A failed SMB request, by kind. {@link #getMessage()} is a German, user-facing sentence that names
 * the resource but never a credential; no cause is attached, so no library text reaches a log.
 */
sealed class SmbAccessException extends Exception {

  SmbAccessException(String message) {
    super(message);
  }

  /** The server refused the service account, or accepted it only as a guest. */
  static final class Authentication extends SmbAccessException {
    Authentication(String message) {
      super(message);
    }
  }

  /** The server has no share of that name. */
  static final class ShareNotFound extends SmbAccessException {
    ShareNotFound(String message) {
      super(message);
    }
  }

  /** The service account may not open the share, folder or file. */
  static final class AccessDenied extends SmbAccessException {
    AccessDenied(String message) {
      super(message);
    }
  }

  /** The folder or file does not exist (any more). */
  static final class NotFound extends SmbAccessException {
    NotFound(String message) {
      super(message);
    }
  }

  /**
   * The path ends in or runs through a link the connector does not follow - a symbolic link, a
   * junction, a DFS link - or through more links than it allows.
   */
  static final class Link extends SmbAccessException {
    Link(String message) {
      super(message);
    }
  }

  /** A download exceeded its size bound. */
  static final class TooLarge extends SmbAccessException {
    TooLarge(String message) {
      super(message);
    }
  }

  /**
   * No request will do better: host blocked by the target validation, unreachable, a timeout, a
   * refused signature, or a share the connector does not serve (DFS namespace, printer).
   */
  static final class Unreachable extends SmbAccessException {
    Unreachable(String message) {
      super(message);
    }
  }

  /** This one request failed (a file locked by another program, an odd answer). */
  static final class Transient extends SmbAccessException {
    Transient(String message) {
      super(message);
    }
  }
}
