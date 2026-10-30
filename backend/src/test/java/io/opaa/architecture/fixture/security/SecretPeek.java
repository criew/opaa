package io.opaa.architecture.fixture.security;

import io.opaa.security.CipherBinding;
import io.opaa.security.SettingsEncryptor;

/** Decrypts a stored secret without a building block that binds it to its target. */
public class SecretPeek {

  public String reveal(SettingsEncryptor encryptor, String ciphertext) {
    return encryptor.decrypt(ciphertext, new CipherBinding("fixture", "secret", "1"));
  }
}
