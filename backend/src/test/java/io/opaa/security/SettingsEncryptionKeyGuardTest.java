package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SettingsEncryptionKeyGuard} (#756) - no Spring context needed, since the
 * guard's whole behaviour is calling {@link SettingsEncryptor#validateKeyEagerly()} once.
 */
class SettingsEncryptionKeyGuardTest {

  @Test
  void startsUpWithAValidKey() {
    byte[] key = new byte[32];
    SettingsEncryptor encryptor =
        new SettingsEncryptor(
            new SettingsEncryptionProperties(Base64.getEncoder().encodeToString(key)));

    assertThatCode(() -> new SettingsEncryptionKeyGuard(encryptor).rejectMissingOrInvalidKey())
        .doesNotThrowAnyException();
  }

  @Test
  void refusesToStartWithoutAConfiguredKey() {
    SettingsEncryptor encryptor = new SettingsEncryptor(new SettingsEncryptionProperties(null));

    assertThatThrownBy(() -> new SettingsEncryptionKeyGuard(encryptor).rejectMissingOrInvalidKey())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_SETTINGS_ENCRYPTION_KEY");
  }

  @Test
  void refusesToStartWithAnInvalidKey() {
    SettingsEncryptor encryptor =
        new SettingsEncryptor(new SettingsEncryptionProperties("not-valid-base64!!!"));

    assertThatThrownBy(() -> new SettingsEncryptionKeyGuard(encryptor).rejectMissingOrInvalidKey())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_SETTINGS_ENCRYPTION_KEY");
  }
}
