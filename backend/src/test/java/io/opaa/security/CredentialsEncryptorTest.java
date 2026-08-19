package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CredentialsEncryptor} (#483) - no Spring context, no database, so these run
 * as fast unit tests rather than the {@code @Testcontainers} integration tests exercising the full
 * JPA round trip ({@code KnowledgeLibraryServiceIntegrationTest}).
 */
class CredentialsEncryptorTest {

  private static String validBase64Key() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) i;
    }
    return Base64.getEncoder().encodeToString(key);
  }

  private CredentialsEncryptor encryptorWithKey(String base64Key) {
    return new CredentialsEncryptor(new CredentialsEncryptionProperties(base64Key));
  }

  @Test
  void encryptsAndDecryptsBackToTheOriginalPlaintext() {
    CredentialsEncryptor encryptor = encryptorWithKey(validBase64Key());

    String encrypted = encryptor.encrypt("admin:super-secret-password");

    assertThat(encrypted).startsWith("enc:v1:");
    assertThat(encrypted).doesNotContain("admin:super-secret-password");
    assertThat(encryptor.decrypt(encrypted)).isEqualTo("admin:super-secret-password");
  }

  @Test
  void encryptingTheSameValueTwiceProducesDifferentCiphertextsBecauseTheIvIsRandom() {
    CredentialsEncryptor encryptor = encryptorWithKey(validBase64Key());

    String first = encryptor.encrypt("admin:secret");
    String second = encryptor.encrypt("admin:secret");

    assertThat(first).isNotEqualTo(second);
    assertThat(encryptor.decrypt(first)).isEqualTo("admin:secret");
    assertThat(encryptor.decrypt(second)).isEqualTo("admin:secret");
  }

  @Test
  void nullAndBlankPlaintextPassThroughUnchanged() {
    CredentialsEncryptor encryptor = encryptorWithKey(validBase64Key());

    assertThat(encryptor.encrypt(null)).isNull();
    assertThat(encryptor.encrypt("")).isEmpty();
  }

  @Test
  void decryptingALegacyPreIssue483CleartextValueReturnsItUnchanged() {
    // A value written before #483 carries no "enc:v1:" prefix at all.
    CredentialsEncryptor encryptor = encryptorWithKey(validBase64Key());

    assertThat(encryptor.decrypt("admin:legacy-plaintext-secret"))
        .isEqualTo("admin:legacy-plaintext-secret");
  }

  @Test
  void decryptingALegacyValueRequiresNoKeyAtAll() {
    CredentialsEncryptor encryptor = encryptorWithKey(null);

    assertThat(encryptor.decrypt("admin:legacy-plaintext-secret"))
        .isEqualTo("admin:legacy-plaintext-secret");
  }

  @Test
  void encryptingWithoutAConfiguredKeyRaisesAClearException() {
    CredentialsEncryptor encryptor = encryptorWithKey(null);

    assertThatThrownBy(() -> encryptor.encrypt("admin:secret"))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class)
        .hasMessageContaining("OPAA_CREDENTIALS_ENCRYPTION_KEY");
  }

  @Test
  void encryptingWithAnInvalidBase64KeyRaisesAClearException() {
    CredentialsEncryptor encryptor = encryptorWithKey("not-valid-base64!!!");

    assertThatThrownBy(() -> encryptor.encrypt("admin:secret"))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void encryptingWithAWrongLengthKeyRaisesAClearException() {
    CredentialsEncryptor encryptor =
        encryptorWithKey(Base64.getEncoder().encodeToString(new byte[16])); // AES-128, not 256

    assertThatThrownBy(() -> encryptor.encrypt("admin:secret"))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class)
        .hasMessageContaining("256-Bit");
  }

  @Test
  void decryptingAValueWithAnUnknownVersionPrefixRaisesAClearExceptionInsteadOfPassingItThrough() {
    // PR #504 review, finding 5: only the exact "enc:v1:" prefix is treated as encrypted-and-
    // decryptable; anything else that still starts with "enc:" must never be returned as if it
    // were cleartext, so future format/version changes cannot be silently misread.
    CredentialsEncryptor encryptor = encryptorWithKey(validBase64Key());

    assertThatThrownBy(() -> encryptor.decrypt("enc:v2:whatever-a-future-format-looks-like"))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void
      decryptingAMalformedBase64PayloadRaisesTheSameClearExceptionRatherThanAnUncaughtIllegalArgumentException() {
    // PR #504 review, finding 2: Base64.getDecoder().decode(...) must be caught alongside the
    // other decrypt failure modes, not left to escape as a bare IllegalArgumentException that
    // would otherwise be misrouted to GlobalExceptionHandler's generic 400 handler with a raw JDK
    // message.
    CredentialsEncryptor encryptor = encryptorWithKey(validBase64Key());

    assertThatThrownBy(() -> encryptor.decrypt("enc:v1:not-valid-base64!!!"))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void decryptingWithTheWrongKeyRaisesAClearExceptionInsteadOfSilentlyReturningGarbage() {
    CredentialsEncryptor writer = encryptorWithKey(validBase64Key());
    String encrypted = writer.encrypt("admin:secret");

    byte[] otherKey = new byte[32];
    otherKey[0] = 1; // different from validBase64Key()'s all-sequential bytes
    CredentialsEncryptor reader = encryptorWithKey(Base64.getEncoder().encodeToString(otherKey));

    assertThatThrownBy(() -> reader.decrypt(encrypted))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }
}
