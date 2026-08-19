package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptionProperties;
import io.opaa.security.CredentialsEncryptor;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SourceCredentialsConverter} (PR #504 review, finding 1) - no Spring
 * context, so these exercise the converter's own read/write failure handling directly rather than
 * through a full JPA round trip ({@code UrlIndexingExecutorCredentialsTest}, {@code
 * KnowledgeLibraryServiceIntegrationTest} cover that).
 */
class SourceCredentialsConverterTest {

  private static String validBase64Key() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) i;
    }
    return Base64.getEncoder().encodeToString(key);
  }

  private static SourceCredentialsConverter converterWithKey(String base64Key) {
    return new SourceCredentialsConverter(
        new CredentialsEncryptor(new CredentialsEncryptionProperties(base64Key)));
  }

  @Test
  void roundTripsAPlaintextValueThroughEncryptionAndBackUnchanged() {
    SourceCredentialsConverter converter = converterWithKey(validBase64Key());

    String stored = converter.convertToDatabaseColumn("admin:super-secret-password");

    assertThat(stored).startsWith("enc:v1:");
    assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("admin:super-secret-password");
  }

  @Test
  void readingAValueThatCannotBeDecryptedWithTheCurrentKeyReturnsNullInsteadOfThrowing() {
    // PR #504 review, finding 1: convertToEntityAttribute runs on every hydration of a
    // KnowledgeLibrary row - one row with an undecryptable value (key lost/rotated) must not fail
    // the whole read (e.g. GET /api/v1/libraries for every library sharing that key).
    SourceCredentialsConverter writer = converterWithKey(validBase64Key());
    String encryptedWithOldKey = writer.convertToDatabaseColumn("admin:super-secret-password");

    byte[] otherKey = new byte[32];
    otherKey[0] = 1; // different from validBase64Key()'s all-sequential bytes
    SourceCredentialsConverter reader =
        converterWithKey(Base64.getEncoder().encodeToString(otherKey));

    assertThat(reader.convertToEntityAttribute(encryptedWithOldKey)).isNull();
  }

  @Test
  void readingAValueWithAnUnknownVersionPrefixReturnsNullInsteadOfThrowing() {
    // PR #504 review, finding 5: never returned as cleartext, but reading still fails soft.
    SourceCredentialsConverter converter = converterWithKey(validBase64Key());

    assertThat(converter.convertToEntityAttribute("enc:v2:whatever-a-future-format-looks-like"))
        .isNull();
  }

  @Test
  void readingAMalformedStoredValueReturnsNullInsteadOfThrowing() {
    // PR #504 review, finding 2: the Base64 decode failure inside CredentialsEncryptor.decrypt
    // must also be caught here, not just genuine key mismatches.
    SourceCredentialsConverter converter = converterWithKey(validBase64Key());

    assertThat(converter.convertToEntityAttribute("enc:v1:not-valid-base64!!!")).isNull();
  }

  @Test
  void readingALegacyPreIssue483CleartextValueReturnsItUnchanged() {
    SourceCredentialsConverter converter = converterWithKey(validBase64Key());

    assertThat(converter.convertToEntityAttribute("admin:legacy-plaintext-secret"))
        .isEqualTo("admin:legacy-plaintext-secret");
  }

  @Test
  void writingWithoutAConfiguredKeyStillFailsHardRatherThanSilentlyDroppingCredentials() {
    // PR #504 review, finding 1: reads fail soft, writes must keep failing hard.
    SourceCredentialsConverter converter = converterWithKey(null);

    assertThatThrownBy(() -> converter.convertToDatabaseColumn("admin:secret"))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }
}
