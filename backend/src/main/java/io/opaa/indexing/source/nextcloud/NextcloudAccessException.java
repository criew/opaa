package io.opaa.indexing.source.nextcloud;

/**
 * A failed request to a Nextcloud, by kind. {@link #getMessage()} is a German, user-facing sentence
 * that names the resource and the status but never a credential or a raw answer.
 */
sealed class NextcloudAccessException extends Exception {

  NextcloudAccessException(String message) {
    super(message);
  }

  /** The instance refused the user name or the app password ({@code 401}). */
  static final class Authentication extends NextcloudAccessException {
    Authentication(String message) {
      super(message);
    }
  }

  /**
   * The instance accepted no request of the technical user at the sign-in ({@code 403} on the
   * principal): a locked or disabled account, or an app password without file access - not the
   * secret itself, which a {@code 401} rejects.
   */
  static final class SignInRefused extends NextcloudAccessException {
    SignInRefused(String message) {
      super(message);
    }
  }

  /** The technical user may not read the resource ({@code 403}). */
  static final class Forbidden extends NextcloudAccessException {
    Forbidden(String message) {
      super(message);
    }
  }

  /**
   * The file is listed but the instance cannot open it ({@code 503} on a download while listing
   * works, as Nextcloud answers for a file its storage refuses to read).
   */
  static final class Unopenable extends NextcloudAccessException {
    Unopenable(String message) {
      super(message);
    }
  }

  /** The resource does not exist, or not for this user ({@code 404}). */
  static final class NotFound extends NextcloudAccessException {
    NotFound(String message) {
      super(message);
    }
  }

  /** A download exceeded its size bound. */
  static final class TooLarge extends NextcloudAccessException {
    TooLarge(String message) {
      super(message);
    }
  }

  /**
   * No request will do better: host blocked by the target validation, unreachable, TLS refused, a
   * redirect off the instance, or an answer that is no Nextcloud.
   */
  static final class Unreachable extends NextcloudAccessException {
    Unreachable(String message) {
      super(message);
    }
  }
}
