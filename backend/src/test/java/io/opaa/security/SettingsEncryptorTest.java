package io.opaa.security;

import static io.opaa.security.CredentialsEncryptorTest.KEY;
import static io.opaa.security.CredentialsEncryptorTest.NEW_KEY;
import static io.opaa.security.CredentialsEncryptorTest.OTHER_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SettingsEncryptor} - no Spring context, no database, mirroring {@link
 * CredentialsEncryptorTest} for the sibling class it is modelled after.
 */
class SettingsEncryptorTest {

  private static final UUID ROW = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final EncryptedColumn COLUMN =
      EncryptedColumn.settings("llm_models", "api_key_ciphertext");
  private static final CipherBinding BINDING = COLUMN.row(ROW);

  private static SettingsEncryptor encryptor(String key, String... previous) {
    return encryptor(CipherFormatGate.KEYED, key, previous);
  }

  private static SettingsEncryptor encryptor(
      CipherFormatGate gate, String key, String... previous) {
    return new SettingsEncryptor(new SettingsEncryptionProperties(key, List.of(previous)), gate);
  }

  @Test
  void encryptsAndDecryptsBackToTheOriginalPlaintext() {
    SettingsEncryptor encryptor = encryptor(KEY);

    String encrypted = encryptor.encrypt("sk-secret", BINDING);

    assertThat(encrypted).startsWith("enc:v2:" + encryptor.currentKeyId() + ":");
    assertThat(encrypted).doesNotContain("sk-secret");
    assertThat(encryptor.decrypt(encrypted, BINDING)).isEqualTo("sk-secret");
  }

  @Test
  void encryptingTheSamePlaintextTwiceProducesDifferentCiphertextsBecauseTheIvIsRandom() {
    SettingsEncryptor encryptor = encryptor(KEY);

    assertThat(encryptor.encrypt("sk-secret", BINDING))
        .isNotEqualTo(encryptor.encrypt("sk-secret", BINDING));
  }

  @Test
  void nullAndBlankPlaintextEncryptToNull() {
    SettingsEncryptor encryptor = encryptor(KEY);

    assertThat(encryptor.encrypt(null, BINDING)).isNull();
    assertThat(encryptor.encrypt("", BINDING)).isNull();
    assertThat(encryptor.encrypt("   ", BINDING)).isNull();
  }

  @Test
  void nullAndBlankStoredValueDecryptToNull() {
    SettingsEncryptor encryptor = encryptor(KEY);

    assertThat(encryptor.decrypt(null, BINDING)).isNull();
    assertThat(encryptor.decrypt("", BINDING)).isNull();
  }

  @Test
  void modelWithoutAnApiKeyNeedsNoKeyAtAllToRoundTrip() {
    SettingsEncryptor encryptor = encryptor(null);

    assertThat(encryptor.encrypt(null, BINDING)).isNull();
    assertThat(encryptor.decrypt(null, BINDING)).isNull();
  }

  @Test
  void encryptingWithoutAConfiguredKeyRaisesAClearException() {
    assertThatThrownBy(() -> encryptor(null).encrypt("sk-secret", BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_SETTINGS_ENCRYPTION_KEY");
  }

  @Test
  void encryptingWithAWrongLengthKeyRaisesAClearException() {
    SettingsEncryptor encryptor = encryptor(Base64.getEncoder().encodeToString(new byte[16]));

    assertThatThrownBy(() -> encryptor.encrypt("sk-secret", BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("256-Bit");
  }

  @Test
  void decryptingAValueWithoutAKnownPrefixRaisesAClearException() {
    SettingsEncryptor encryptor = encryptor(KEY);

    assertThatThrownBy(() -> encryptor.decrypt("plain-not-encrypted-at-all", BINDING))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> encryptor.decrypt("enc:v3:a-future-format", BINDING))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> encryptor.decrypt("enc:v2:no-key-id-separator", BINDING))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void decryptingWithTheWrongKeyOrAtAnotherRowRaisesAClearException() {
    String encrypted = encryptor(KEY).encrypt("sk-secret", BINDING);

    assertThatThrownBy(() -> encryptor(OTHER_KEY).decrypt(encrypted, BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_SETTINGS_ENCRYPTION_PREVIOUS_KEYS");
    assertThatThrownBy(() -> encryptor(KEY).decrypt(encrypted, COLUMN.row(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void afterARotationTheOldKeyOnlyDecryptsAndRewrapMovesValuesToTheNewOne() {
    String keyed = encryptor(KEY).encrypt("sk-keyed", BINDING);
    String legacyFormat = encryptor(() -> false, KEY).encrypt("sk-v1", BINDING);
    SettingsEncryptor rotated = encryptor(NEW_KEY, KEY);

    assertThat(rotated.decrypt(keyed, BINDING)).isEqualTo("sk-keyed");
    assertThat(rotated.decrypt(legacyFormat, BINDING)).isEqualTo("sk-v1");
    assertThat(encryptor(NEW_KEY).decrypt(rotated.rewrap(keyed, BINDING, true), BINDING))
        .isEqualTo("sk-keyed");
    assertThat(encryptor(NEW_KEY).decrypt(rotated.rewrap(legacyFormat, BINDING, true), BINDING))
        .isEqualTo("sk-v1");
  }

  @Test
  void decryptsValuesStoredInTheEstablishedFormats() {
    // written with KEY by an independent AES-GCM implementation; guards against format drift
    String v1 = "enc:v1:E6yfbKrCoHYne+1ZL6yQTVi1ySRE/xIhDJD7CJCKEcHldHEI9BCmykJ7jQ==";
    String v2 =
        "enc:v2:585b259a1d5c0ce7:ZGVmZ2hpamtsbW5vO3DzEhyaIrNfEjbFsQATnV1+pyLCpgb6HUl3o/DscQ==";

    assertThat(encryptor(KEY).decrypt(v1, BINDING)).isEqualTo("sk-test-api-key");
    assertThat(encryptor(KEY).decrypt(v2, BINDING)).isEqualTo("sk-test-api-key");
  }

  @Test
  void onceTheUnboundFormsAreRetiredOnlyTheKeyedFormatIsReadAndWritten() {
    String v1 = encryptor(() -> false, KEY).encrypt("admin:secret", BINDING);
    String v2 = encryptor(KEY).encrypt("admin:secret", BINDING);
    LegacyCipherRetirement all =
        new LegacyCipherRetirement() {
          @Override
          public boolean refusesLegacy(EncryptionPurpose purpose) {
            return true;
          }

          @Override
          public void retire(EncryptionPurpose purpose) {}
        };
    SettingsEncryptor retired =
        new SettingsEncryptor(new SettingsEncryptionProperties(KEY, List.of()), () -> false, all);

    assertThat(retired.decrypt(v2, BINDING)).isEqualTo("admin:secret");
    assertThatThrownBy(() -> retired.decrypt(v1, BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("enc:v1");
    assertThat(retired.encrypt("admin:secret", BINDING)).startsWith("enc:v2:");
    assertThat(encryptor(() -> false, KEY).decrypt(v1, BINDING))
        .as("not retired")
        .isEqualTo("admin:secret");
  }
}
