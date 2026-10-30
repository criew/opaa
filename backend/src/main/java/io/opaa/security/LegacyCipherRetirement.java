package io.opaa.security;

/**
 * Whether the unbound forms of a purpose - {@code enc:v1:} and, for {@link CredentialsEncryptor},
 * legacy cleartext - are refused (ADR-0064, Entscheidung 6). {@link CiphertextRewrap} retires them
 * once no column of the purpose holds such a value any more and the keyed format is open; from then
 * on a value of that form found in a column is refused instead of read, and new values are always
 * written keyed.
 */
public interface LegacyCipherRetirement {

  /** Never refuses and remembers nothing; for tests and tools without a database. */
  LegacyCipherRetirement NEVER =
      new LegacyCipherRetirement() {
        @Override
        public boolean refusesLegacy(EncryptionPurpose purpose) {
          return false;
        }

        @Override
        public void retire(EncryptionPurpose purpose) {}
      };

  /**
   * Whether {@code purpose} refuses its unbound forms; never once the keyed format is held back.
   */
  boolean refusesLegacy(EncryptionPurpose purpose);

  /** Records for every instance that {@code purpose} refuses its unbound forms from now on. */
  void retire(EncryptionPurpose purpose);
}
