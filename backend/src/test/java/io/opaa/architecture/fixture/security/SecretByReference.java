package io.opaa.architecture.fixture.security;

import io.opaa.security.CipherBinding;
import io.opaa.security.SettingsEncryptor;
import java.util.function.BiFunction;

/** Decrypts through a method reference, passed on to whoever applies it. */
public class SecretByReference {

  public BiFunction<String, CipherBinding, String> decryption(SettingsEncryptor encryptor) {
    return encryptor::decrypt;
  }
}
