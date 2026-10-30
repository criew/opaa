package io.opaa.security;

import static io.opaa.security.CredentialsEncryptorTest.KEY;
import static io.opaa.security.CredentialsEncryptorTest.NEW_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EncryptedCredentials}, the row-bound value of a converted attribute. */
class EncryptedCredentialsTest {

  private static final EncryptedColumn COLUMN =
      EncryptedColumn.credentials("knowledge_libraries", "source_credentials");
  private static final CipherBinding ROW = COLUMN.row(UUID.randomUUID());

  private static CredentialsEncryptor encryptor(String key, String... previous) {
    return new CredentialsEncryptor(
        new CredentialsEncryptionProperties(key, List.of(previous)), CipherFormatGate.KEYED);
  }

  @Test
  void aNewValueIsEncryptedForItsRowAndReadsBackAfterALoad() {
    CredentialsEncryptor encryptor = encryptor(KEY);

    String column = EncryptedCredentials.of("admin:geheim", ROW).toColumn(encryptor);

    assertThat(column).startsWith("enc:v2:").doesNotContain("admin:geheim");
    assertThat(EncryptedCredentials.loaded(column, encryptor).read(ROW)).isEqualTo("admin:geheim");
  }

  @Test
  void aLoadedValueGoesBackAsStoredEvenWhenItsKeyIsMissing() {
    String column = EncryptedCredentials.of("admin:geheim", ROW).toColumn(encryptor(KEY));
    EncryptedCredentials loaded = EncryptedCredentials.loaded(column, encryptor(NEW_KEY));

    assertThatThrownBy(() -> loaded.read(ROW))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
    assertThat(loaded.toColumn(encryptor(NEW_KEY))).isEqualTo(column);
  }

  @Test
  void aLoadedValueReadsAtItsOwnRowOnly() {
    CredentialsEncryptor encryptor = encryptor(KEY);
    String column = EncryptedCredentials.of("admin:geheim", ROW).toColumn(encryptor);

    assertThatThrownBy(
            () ->
                EncryptedCredentials.loaded(column, encryptor).read(COLUMN.row(UUID.randomUUID())))
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
  }

  @Test
  void equalityFollowsTheValueSoThatDirtyCheckingSeesOnlyARealChange() {
    CredentialsEncryptor encryptor = encryptor(KEY);
    String column = EncryptedCredentials.of("admin:geheim", ROW).toColumn(encryptor);

    assertThat(EncryptedCredentials.loaded(column, encryptor))
        .isEqualTo(EncryptedCredentials.loaded(column, encryptor));
    assertThat(EncryptedCredentials.of("admin:geheim", ROW))
        .isEqualTo(EncryptedCredentials.of("admin:geheim", ROW))
        .isNotEqualTo(EncryptedCredentials.loaded(column, encryptor));
    assertThat(EncryptedCredentials.of(null, ROW)).isNull();
    assertThat(EncryptedCredentials.loaded(null, encryptor)).isNull();
    assertThat(EncryptedCredentials.of("admin:geheim", ROW).toString())
        .doesNotContain("admin:geheim");
  }
}
