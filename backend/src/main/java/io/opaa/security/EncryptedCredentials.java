package io.opaa.security;

import java.util.Objects;

/**
 * The value of an entity attribute that a JPA converter stores through {@link CredentialsEncryptor}
 * bound to its row (ADR-0064). A converter never learns which row it converts, so the binding
 * travels with the value: the entity creates a new value with {@link #of(String, CipherBinding)}
 * from its own id, and reads a loaded one with {@link #read(CipherBinding)}, again naming its id.
 *
 * <p>Immutable, with value equality, so that dirty checking sees a change exactly when the entity
 * set a new value: a loaded value equals itself until it is replaced, and is written back as it was
 * read - also when its key is missing and it cannot be decrypted.
 */
public final class EncryptedCredentials {

  private final String plaintext;
  private final CipherBinding binding;
  private final String stored;
  private final CredentialsEncryptor encryptor;

  private EncryptedCredentials(
      String plaintext, CipherBinding binding, String stored, CredentialsEncryptor encryptor) {
    this.plaintext = plaintext;
    this.binding = binding;
    this.stored = stored;
    this.encryptor = encryptor;
  }

  /** A new value for the row of {@code binding}; {@code null} for none. */
  public static EncryptedCredentials of(String plaintext, CipherBinding binding) {
    return plaintext == null
        ? null
        : new EncryptedCredentials(
            plaintext, Objects.requireNonNull(binding, "binding"), null, null);
  }

  /** A value as the column holds it, read through {@code encryptor}; {@code null} for none. */
  public static EncryptedCredentials loaded(String stored, CredentialsEncryptor encryptor) {
    return stored == null ? null : new EncryptedCredentials(null, null, stored, encryptor);
  }

  /** What the column receives: the loaded value unchanged, a new one encrypted for its row. */
  public String toColumn(CredentialsEncryptor encryptor) {
    return stored != null ? stored : encryptor.encrypt(plaintext, binding);
  }

  /**
   * The value in clear, a loaded one decrypted for the row of {@code binding}.
   *
   * @throws CredentialsEncryptionKeyMissingException when a loaded value cannot be decrypted
   */
  public String read(CipherBinding binding) {
    return stored != null ? encryptor.decrypt(stored, binding) : plaintext;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EncryptedCredentials that
        && Objects.equals(plaintext, that.plaintext)
        && Objects.equals(binding, that.binding)
        && Objects.equals(stored, that.stored);
  }

  @Override
  public int hashCode() {
    return Objects.hash(plaintext, binding, stored);
  }

  @Override
  public String toString() {
    return stored != null ? "EncryptedCredentials[stored]" : "EncryptedCredentials[new]";
  }
}
