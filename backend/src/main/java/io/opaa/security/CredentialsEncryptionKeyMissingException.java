package io.opaa.security;

/**
 * Raised by {@link CredentialsEncryptor#encrypt(String)} when a caller attempts to persist a
 * non-blank {@code sourceCredentials} value without {@code
 * opaa.security.credentials.encryption-key} (env {@code OPAA_CREDENTIALS_ENCRYPTION_KEY})
 * configured, or with a value that is not a valid Base64-encoded AES-256 key.
 *
 * <p>Deliberately a distinct, unchecked exception rather than {@link IllegalStateException} (#483):
 * {@code io.opaa.api.GlobalExceptionHandler} maps it to a clear {@code 503} instead of letting it
 * fall through to the generic {@code 500} handler, which would give an operator no indication of
 * what to fix. Never carries the submitted plaintext credentials in its message.
 */
public class CredentialsEncryptionKeyMissingException extends RuntimeException {

  public CredentialsEncryptionKeyMissingException(String message) {
    super(message);
  }

  public CredentialsEncryptionKeyMissingException(String message, Throwable cause) {
    super(message, cause);
  }
}
