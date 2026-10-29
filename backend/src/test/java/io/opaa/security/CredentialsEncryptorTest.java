package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CredentialsEncryptor} - no Spring context, no database, so these run as
 * fast unit tests rather than the {@code @Testcontainers} integration tests exercising the full JPA
 * round trip ({@code KnowledgeLibraryServiceIntegrationTest}).
 */
class CredentialsEncryptorTest {

  static final String KEY = key(0);
  static final String NEW_KEY = key(1);
  static final String OTHER_KEY = key(2);

  private static final UUID ROW = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final EncryptedColumn COLUMN =
      EncryptedColumn.credentials("knowledge_libraries", "source_credentials");
  private static final CipherBinding BINDING = COLUMN.row(ROW);

  /** 32 bytes counting up from {@code offset}; {@code key(0)} is the key of the fixtures. */
  static String key(int offset) {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) (i + offset);
    }
    return Base64.getEncoder().encodeToString(key);
  }

  private static CredentialsEncryptor encryptor(String key, String... previous) {
    return encryptor(CipherFormatGate.KEYED, key, previous);
  }

  private static CredentialsEncryptor encryptor(
      CipherFormatGate gate, String key, String... previous) {
    return new CredentialsEncryptor(
        new CredentialsEncryptionProperties(key, List.of(previous)), gate);
  }

  @Test
  void encryptsAndDecryptsBackToTheOriginalPlaintext() {
    CredentialsEncryptor encryptor = encryptor(KEY);

    String encrypted = encryptor.encrypt("admin:super-secret-password", BINDING);

    assertThat(encrypted).startsWith("enc:v2:" + encryptor.currentKeyId() + ":");
    assertThat(encrypted).doesNotContain("admin:super-secret-password");
    assertThat(encryptor.decrypt(encrypted, BINDING)).isEqualTo("admin:super-secret-password");
  }

  @Test
  void encryptingTheSameValueTwiceProducesDifferentCiphertextsBecauseTheIvIsRandom() {
    CredentialsEncryptor encryptor = encryptor(KEY);

    String first = encryptor.encrypt("admin:secret", BINDING);
    String second = encryptor.encrypt("admin:secret", BINDING);

    assertThat(first).isNotEqualTo(second);
    assertThat(encryptor.decrypt(first, BINDING)).isEqualTo("admin:secret");
    assertThat(encryptor.decrypt(second, BINDING)).isEqualTo("admin:secret");
  }

  @Test
  void nullAndBlankPlaintextPassThroughUnchanged() {
    CredentialsEncryptor encryptor = encryptor(KEY);

    assertThat(encryptor.encrypt(null, BINDING)).isNull();
    assertThat(encryptor.encrypt("", BINDING)).isEmpty();
  }

  @Test
  void decryptingALegacyCleartextValueReturnsItUnchanged() {
    assertThat(encryptor(KEY).decrypt("admin:legacy-plaintext-secret", BINDING))
        .isEqualTo("admin:legacy-plaintext-secret");
  }

  @Test
  void decryptingALegacyValueRequiresNoKeyAtAll() {
    assertThat(encryptor(null).decrypt("admin:legacy-plaintext-secret", BINDING))
        .isEqualTo("admin:legacy-plaintext-secret");
  }

  @Test
  void encryptingWithoutAConfiguredKeyRaisesAClearException() {
    assertThatThrownBy(() -> encryptor(null).encrypt("admin:secret", BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class)
        .hasMessageContaining("OPAA_CREDENTIALS_ENCRYPTION_KEY");
  }

  @Test
  void encryptingWithAnInvalidBase64KeyRaisesAClearException() {
    assertThatThrownBy(() -> encryptor("not-valid-base64!!!").encrypt("admin:secret", BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void encryptingWithAWrongLengthKeyRaisesAClearException() {
    CredentialsEncryptor encryptor = encryptor(Base64.getEncoder().encodeToString(new byte[16]));

    assertThatThrownBy(() -> encryptor.encrypt("admin:secret", BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class)
        .hasMessageContaining("256-Bit");
  }

  @Test
  void anInvalidPreviousKeyIsNamedByItsOwnVariable() {
    assertThatThrownBy(() -> encryptor(KEY, "not-valid-base64!!!").encrypt("admin:x", BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class)
        .hasMessageContaining("OPAA_CREDENTIALS_ENCRYPTION_PREVIOUS_KEYS");
  }

  @Test
  void decryptingAValueWithAnUnknownVersionPrefixRaisesAClearExceptionInsteadOfPassingItThrough() {
    // anything that starts with "enc:" in an unknown format must never be returned as cleartext
    assertThatThrownBy(() -> encryptor(KEY).decrypt("enc:v3:a-future-format", BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void decryptingAMalformedBase64PayloadRaisesTheSameClearException() {
    // a bare IllegalArgumentException would be misrouted to the generic 400 handler
    assertThatThrownBy(() -> encryptor(KEY).decrypt("enc:v1:not-valid-base64!!!", BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void decryptingWithTheWrongKeyRaisesAClearExceptionInsteadOfSilentlyReturningGarbage() {
    String encrypted = encryptor(CipherFormatGate.KEYED, KEY).encrypt("admin:secret", BINDING);
    String legacyFormat = encryptor(() -> false, KEY).encrypt("admin:secret", BINDING);

    assertThatThrownBy(() -> encryptor(OTHER_KEY).decrypt(encrypted, BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class)
        .hasMessageContaining("OPAA_CREDENTIALS_ENCRYPTION_PREVIOUS_KEYS");
    assertThatThrownBy(() -> encryptor(OTHER_KEY).decrypt(legacyFormat, BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void aCiphertextMovedToAnotherRowOrColumnDoesNotDecryptThere() {
    CredentialsEncryptor encryptor = encryptor(KEY);
    String encrypted = encryptor.encrypt("admin:secret", BINDING);

    assertThatThrownBy(() -> encryptor.decrypt(encrypted, COLUMN.row(UUID.randomUUID())))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
    assertThatThrownBy(
            () ->
                encryptor.decrypt(
                    encrypted,
                    EncryptedColumn.credentials("knowledge_libraries", "source_webhook_secret")
                        .row(ROW)))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void rebindMovesAValueToAnotherRow() {
    CredentialsEncryptor encryptor = encryptor(KEY);
    CipherBinding other = COLUMN.row(UUID.randomUUID());

    String moved = encryptor.rebind(encryptor.encrypt("admin:secret", BINDING), BINDING, other);

    assertThat(encryptor.decrypt(moved, other)).isEqualTo("admin:secret");
    assertThatThrownBy(() -> encryptor.decrypt(moved, BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void aClosedFormatGateWritesTheFormatAnOlderVersionReads() {
    String stored = encryptor(() -> false, KEY).encrypt("admin:secret", BINDING);

    assertThat(stored).startsWith("enc:v1:");
    assertThat(encryptor(KEY).decrypt(stored, COLUMN.row(UUID.randomUUID())))
        .isEqualTo("admin:secret");
  }

  @Test
  void afterARotationValuesUnderThePreviousKeyStayReadable() {
    String keyed = encryptor(KEY).encrypt("admin:keyed", BINDING);
    String legacyFormat = encryptor(() -> false, KEY).encrypt("admin:v1", BINDING);

    CredentialsEncryptor rotated = encryptor(NEW_KEY, KEY);

    assertThat(rotated.decrypt(keyed, BINDING)).isEqualTo("admin:keyed");
    assertThat(rotated.decrypt(legacyFormat, BINDING)).isEqualTo("admin:v1");
    assertThat(rotated.encrypt("admin:new", BINDING))
        .startsWith("enc:v2:" + rotated.currentKeyId() + ":")
        .doesNotStartWith("enc:v2:" + encryptor(KEY).currentKeyId() + ":");
  }

  @Test
  void withoutThePreviousKeyTheMessageNamesTheVariableToSet() {
    String keyed = encryptor(KEY).encrypt("admin:keyed", BINDING);

    assertThatThrownBy(() -> encryptor(NEW_KEY).decrypt(keyed, BINDING))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class)
        .hasMessageContaining("OPAA_CREDENTIALS_ENCRYPTION_PREVIOUS_KEYS");
  }

  @Test
  void rewrapPutsAValueUnderTheCurrentKeyAndLeavesACurrentOneAlone() {
    String keyed = encryptor(KEY).encrypt("admin:keyed", BINDING);
    String legacyFormat = encryptor(() -> false, KEY).encrypt("admin:v1", BINDING);
    CredentialsEncryptor rotated = encryptor(NEW_KEY, KEY);

    String rewrapped = rotated.rewrap(keyed, BINDING, true);
    String converted = rotated.rewrap(legacyFormat, BINDING, true);

    assertThat(rewrapped).startsWith("enc:v2:" + rotated.currentKeyId() + ":");
    assertThat(converted).startsWith("enc:v2:" + rotated.currentKeyId() + ":");
    assertThat(encryptor(NEW_KEY).decrypt(rewrapped, BINDING)).isEqualTo("admin:keyed");
    assertThat(encryptor(NEW_KEY).decrypt(converted, BINDING)).isEqualTo("admin:v1");
    assertThat(rotated.rewrap(rewrapped, BINDING, true)).isSameAs(rewrapped);
  }

  @Test
  void rewrapRefusesAValueUnderAKeyItDoesNotHold() {
    String foreign = encryptor(OTHER_KEY).encrypt("admin:secret", BINDING);

    assertThatThrownBy(() -> encryptor(NEW_KEY, KEY).rewrap(foreign, BINDING, true))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void theKeyIdDependsOnTheKeyAlone() {
    assertThat(encryptor(KEY).currentKeyId())
        .isEqualTo(encryptor(KEY, NEW_KEY).currentKeyId())
        .isEqualTo("585b259a1d5c0ce7")
        .isNotEqualTo(encryptor(NEW_KEY).currentKeyId());
  }

  @Test
  void decryptsValuesStoredInTheEstablishedFormats() {
    // written with KEY by an independent AES-GCM implementation; guards against format drift
    String v1 =
        "enc:v1:CSE4oxTU2M7odI8RjDLuJXUFWYknwhG1pm0mbh9+hjJYXQCXMNKOGotCjMLFtWlXk4RheHYNVQ==";
    String v2 =
        "enc:v2:585b259a1d5c0ce7:ZGVmZ2hpamtsbW5vKX+zDxfTJetOBy3FqQAJjye2K3rqH4AFyKPImeYQCSN9SzPVHMNNraWSpg==";

    assertThat(encryptor(KEY).decrypt(v1, BINDING)).isEqualTo("admin:super-secret-password");
    assertThat(encryptor(KEY).decrypt(v2, BINDING)).isEqualTo("admin:super-secret-password");
    assertThat(encryptor(NEW_KEY, KEY).decrypt(v2, BINDING))
        .isEqualTo("admin:super-secret-password");
  }
}
