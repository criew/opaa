package io.opaa.indexing.source.s3;

/**
 * Credentials of an S3 library (ADR-0027, Entscheidung 7): a static access key and secret key,
 * optionally with a session token, stored as one string {@code accessKey:secretKey[:sessionToken]}
 * in {@code knowledge_libraries.source_credentials}. Neither key may contain a colon - MinIO and
 * Ceph let an operator choose them freely, and a colon would be split silently and show up as a
 * misleading {@code SignatureDoesNotMatch}.
 *
 * <p>{@link #toString()} never reveals any part; the adapter is the only place that hands the
 * values to the SDK, so a credential can appear in no log line, exception message or API response
 * by accident.
 */
public record S3Credentials(String accessKey, String secretKey, String sessionToken) {

  public S3Credentials {
    if (accessKey == null || accessKey.isBlank()) {
      throw new InvalidCredentialsFormatException("Der Access Key ist erforderlich.");
    }
    if (secretKey == null || secretKey.isBlank()) {
      throw new InvalidCredentialsFormatException("Der Secret Key ist erforderlich.");
    }
    if (accessKey.indexOf(':') >= 0 || secretKey.indexOf(':') >= 0) {
      throw new InvalidCredentialsFormatException(
          "Access Key und Secret Key dürfen keinen Doppelpunkt enthalten.");
    }
    if (sessionToken != null && sessionToken.isBlank()) {
      sessionToken = null;
    }
  }

  /**
   * Parses the stored form.
   *
   * @throws InvalidCredentialsFormatException with a German, user-facing message when the value is
   *     blank or lacks the {@code accessKey:secretKey} separator
   */
  public static S3Credentials parse(String stored) {
    if (stored == null || stored.isBlank()) {
      throw new InvalidCredentialsFormatException(
          "Für einen S3-Objektspeicher sind Zugangsdaten erforderlich.");
    }
    int first = stored.indexOf(':');
    if (first < 0) {
      throw new InvalidCredentialsFormatException(
          "S3-Zugangsdaten bestehen aus Access Key und Secret Key, getrennt durch einen"
              + " Doppelpunkt (accessKey:secretKey), optional gefolgt von :sessionToken.");
    }
    String accessKey = stored.substring(0, first);
    String rest = stored.substring(first + 1);
    int second = rest.indexOf(':');
    String secretKey = second < 0 ? rest : rest.substring(0, second);
    String sessionToken = second < 0 ? null : rest.substring(second + 1);
    return new S3Credentials(accessKey, secretKey, sessionToken);
  }

  public boolean hasSessionToken() {
    return sessionToken != null;
  }

  /** The stored form, {@code accessKey:secretKey[:sessionToken]}. */
  public String stored() {
    return accessKey + ":" + secretKey + (sessionToken == null ? "" : ":" + sessionToken);
  }

  @Override
  public String toString() {
    return "S3Credentials[accessKey=***, secretKey=***, sessionToken="
        + (sessionToken == null ? "none" : "***")
        + "]";
  }

  /** Thrown by {@link #parse} and the constructor for a value that cannot be a credential. */
  public static final class InvalidCredentialsFormatException extends RuntimeException {
    public InvalidCredentialsFormatException(String message) {
      super(message);
    }
  }
}
