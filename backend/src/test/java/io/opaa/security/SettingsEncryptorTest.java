package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SettingsEncryptor} (#756) - no Spring context, no database, mirroring
 * {@link CredentialsEncryptorTest} for the sibling class it is modelled after.
 */
class SettingsEncryptorTest {

  private static String validBase64Key() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) i;
    }
    return Base64.getEncoder().encodeToString(key);
  }

  private SettingsEncryptor encryptorWithKey(String base64Key) {
    return new SettingsEncryptor(new SettingsEncryptionProperties(base64Key));
  }

  @Test
  void encryptsAndDecryptsBackToTheOriginalPlaintext() {
    SettingsEncryptor encryptor = encryptorWithKey(validBase64Key());

    String encrypted = encryptor.encrypt("sk-super-secret-key");

    assertThat(encrypted).startsWith("enc:v1:");
    assertThat(encrypted).doesNotContain("sk-super-secret-key");
    assertThat(encryptor.decrypt(encrypted)).isEqualTo("sk-super-secret-key");
  }

  @Test
  void encryptingTheSamePlaintextTwiceProducesDifferentCiphertextsBecauseTheIvIsRandom() {
    SettingsEncryptor encryptor = encryptorWithKey(validBase64Key());

    String first = encryptor.encrypt("sk-secret");
    String second = encryptor.encrypt("sk-secret");

    assertThat(first).isNotEqualTo(second);
    assertThat(encryptor.decrypt(first)).isEqualTo("sk-secret");
    assertThat(encryptor.decrypt(second)).isEqualTo("sk-secret");
  }

  @Test
  void nullAndBlankPlaintextEncryptToNull() {
    SettingsEncryptor encryptor = encryptorWithKey(validBase64Key());

    assertThat(encryptor.encrypt(null)).isNull();
    assertThat(encryptor.encrypt("")).isNull();
    assertThat(encryptor.encrypt("   ")).isNull();
  }

  @Test
  void nullAndBlankStoredValueDecryptToNull() {
    SettingsEncryptor encryptor = encryptorWithKey(validBase64Key());

    assertThat(encryptor.decrypt(null)).isNull();
    assertThat(encryptor.decrypt("")).isNull();
  }

  @Test
  void modelWithoutAnApiKeyNeedsNoKeyAtAllToRoundTrip() {
    SettingsEncryptor encryptor = encryptorWithKey(null);

    assertThat(encryptor.encrypt(null)).isNull();
    assertThat(encryptor.decrypt(null)).isNull();
  }

  @Test
  void encryptingWithoutAConfiguredKeyRaisesAClearException() {
    SettingsEncryptor encryptor = encryptorWithKey(null);

    assertThatThrownBy(() -> encryptor.encrypt("sk-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_SETTINGS_ENCRYPTION_KEY");
  }

  @Test
  void encryptingWithAnInvalidBase64KeyRaisesAClearException() {
    SettingsEncryptor encryptor = encryptorWithKey("not-valid-base64!!!");

    assertThatThrownBy(() -> encryptor.encrypt("sk-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_SETTINGS_ENCRYPTION_KEY");
  }

  @Test
  void encryptingWithAWrongLengthKeyRaisesAClearException() {
    SettingsEncryptor encryptor =
        encryptorWithKey(Base64.getEncoder().encodeToString(new byte[16])); // AES-128, not 256

    assertThatThrownBy(() -> encryptor.encrypt("sk-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("256-Bit");
  }

  @Test
  void decryptingAValueWithoutTheCurrentPrefixRaisesAClearException() {
    SettingsEncryptor encryptor = encryptorWithKey(validBase64Key());

    assertThatThrownBy(() -> encryptor.decrypt("plain-not-encrypted-at-all"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> encryptor.decrypt("enc:v2:whatever-a-future-format-looks-like"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void decryptingAMalformedBase64PayloadRaisesAClearException() {
    SettingsEncryptor encryptor = encryptorWithKey(validBase64Key());

    assertThatThrownBy(() -> encryptor.decrypt("enc:v1:not-valid-base64!!!"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void decryptingWithTheWrongKeyRaisesAClearExceptionInsteadOfSilentlyReturningGarbage() {
    SettingsEncryptor writer = encryptorWithKey(validBase64Key());
    String encrypted = writer.encrypt("sk-secret");

    byte[] otherKey = new byte[32];
    otherKey[0] = 1; // different from validBase64Key()'s all-sequential bytes
    SettingsEncryptor reader = encryptorWithKey(Base64.getEncoder().encodeToString(otherKey));

    assertThatThrownBy(() -> reader.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
  }
}
