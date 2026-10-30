package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.security.CipherFormatGate;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptionProperties;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.EncryptedCredentials;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link SourceCredentialsConverter} together with the getters of {@link
 * KnowledgeLibrary} that read what it loads - no Spring context; a load is simulated by placing the
 * converted column value in the entity field, as Hibernate does ({@code
 * UrlIndexingExecutorCredentialsTest}, {@code KnowledgeLibraryServiceIntegrationTest} cover the
 * full JPA round trip).
 */
class SourceCredentialsConverterTest {

  private static final String SECRET = "admin:super-secret-password";

  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(KnowledgeLibrary.class)).addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(KnowledgeLibrary.class)).detachAppender(logs);
  }

  private static String validBase64Key() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) i;
    }
    return Base64.getEncoder().encodeToString(key);
  }

  private static SourceCredentialsConverter converterWithKey(String base64Key) {
    return new SourceCredentialsConverter(
        new CredentialsEncryptor(
            new CredentialsEncryptionProperties(base64Key, List.of()), CipherFormatGate.KEYED));
  }

  private static KnowledgeLibrary library(String credentials) {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Quelle",
        null,
        UUID.randomUUID(),
        SourceType.UPLOAD,
        null,
        "https://quelle.example.org",
        credentials);
  }

  /** What the column of {@code library} receives through {@code converter} on a flush. */
  private static String column(SourceCredentialsConverter converter, KnowledgeLibrary library) {
    return converter.convertToDatabaseColumn(
        (EncryptedCredentials) ReflectionTestUtils.getField(library, "sourceCredentials"));
  }

  /** {@code library} as Hibernate hydrates it from a row holding {@code stored}. */
  private static KnowledgeLibrary loaded(
      KnowledgeLibrary library, SourceCredentialsConverter converter, String stored) {
    ReflectionTestUtils.setField(
        library, "sourceCredentials", converter.convertToEntityAttribute(stored));
    return library;
  }

  @Test
  void roundTripsAPlaintextValueThroughEncryptionBoundToItsLibrary() {
    SourceCredentialsConverter converter = converterWithKey(validBase64Key());
    KnowledgeLibrary library = library(SECRET);

    String stored = column(converter, library);

    assertThat(stored).startsWith("enc:v2:").doesNotContain(SECRET);
    assertThat(loaded(library, converter, stored).getSourceCredentials()).isEqualTo(SECRET);
  }

  @Test
  void theCiphertextOfOneLibraryDoesNotReadAsTheCredentialsOfAnother() {
    SourceCredentialsConverter converter = converterWithKey(validBase64Key());
    String stored = column(converter, library(SECRET));

    assertThat(loaded(library(null), converter, stored).getSourceCredentials()).isNull();
  }

  @Test
  void readingAValueThatCannotBeDecryptedWithTheCurrentKeyReturnsNullInsteadOfThrowing() {
    // convertToEntityAttribute runs on every hydration of a KnowledgeLibrary row - one row with
    // an undecryptable value (key lost) must not fail the whole read.
    KnowledgeLibrary library = library(SECRET);
    String encryptedWithOldKey = column(converterWithKey(validBase64Key()), library);

    byte[] otherKey = new byte[32];
    otherKey[0] = 1;
    SourceCredentialsConverter reader =
        converterWithKey(Base64.getEncoder().encodeToString(otherKey));

    assertThat(loaded(library, reader, encryptedWithOldKey).getSourceCredentials()).isNull();
  }

  @Test
  void readingAValueWithAnUnknownVersionPrefixOrAMalformedValueReturnsNull() {
    SourceCredentialsConverter converter = converterWithKey(validBase64Key());

    assertThat(
            loaded(library(null), converter, "enc:v3:whatever-a-future-format-looks-like")
                .getSourceCredentials())
        .isNull();
    assertThat(
            loaded(library(null), converter, "enc:v1:not-valid-base64!!!").getSourceCredentials())
        .isNull();
  }

  @Test
  void readingALegacyPreIssue483CleartextValueReturnsItUnchanged() {
    SourceCredentialsConverter converter = converterWithKey(validBase64Key());

    assertThat(
            loaded(library(null), converter, "admin:legacy-plaintext-secret")
                .getSourceCredentials())
        .isEqualTo("admin:legacy-plaintext-secret");
  }

  @Test
  void readingWithoutAConfiguredKeyIsNotSilentAndTheWarningCarriesNoSecret() {
    KnowledgeLibrary library = library(SECRET);
    String encrypted = column(converterWithKey(validBase64Key()), library);
    logs.list.clear();

    assertThat(loaded(library, converterWithKey(null), encrypted).getSourceCredentials()).isNull();

    assertThat(logs.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel().levelStr).isEqualTo("WARN");
              assertThat(event.getFormattedMessage())
                  .contains("OPAA_CREDENTIALS_ENCRYPTION_KEY")
                  .doesNotContain(SECRET)
                  .doesNotContain(encrypted);
            });
  }

  @Test
  void anUnreadableValueIsWrittenBackAsStoredAlsoWhenTheGetterResultIsHandedBack() {
    KnowledgeLibrary library = library(SECRET);
    String encrypted = column(converterWithKey(validBase64Key()), library);
    SourceCredentialsConverter keyless = converterWithKey(null);
    loaded(library, keyless, encrypted);

    library.replaceSourceCredentials(library.getSourceCredentials());

    assertThat(column(keyless, library)).isEqualTo(encrypted);
  }

  @Test
  void writingWithoutAConfiguredKeyStillFailsHardRatherThanSilentlyDroppingCredentials() {
    SourceCredentialsConverter converter = converterWithKey(null);
    KnowledgeLibrary library = library("admin:secret");

    assertThatThrownBy(() -> column(converter, library))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }
}
