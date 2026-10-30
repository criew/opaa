package io.opaa.security;

/**
 * Whether the unbound forms of a purpose - {@code enc:v1:} and, for {@link CredentialsEncryptor},
 * legacy cleartext - are refused (ADR-0064, Entscheidung 6). {@link CiphertextRewrap} reports each
 * keyed run's count of such values; once two runs a confirmation interval apart found none and none
 * in between found any, the purpose is retired: from then on such a value found in a column is
 * refused instead of read, and new values are always written keyed. A retirement lapses as soon as
 * an instance of another version has run since, which may have written {@code enc:v1:} again.
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
        public void measured(EncryptionPurpose purpose, int unbound) {}
      };

  /**
   * Whether {@code purpose} refuses its unbound forms; never while {@code
   * OPAA_ENCRYPTION_KEYED_FORMAT=false}.
   */
  boolean refusesLegacy(EncryptionPurpose purpose);

  /**
   * Whether new values of {@code purpose} are written keyed whatever {@link CipherFormatGate} says:
   * once retired, also after the retirement lapsed, because every version running beside it since
   * reads the keyed format.
   */
  default boolean writesKeyedOnly(EncryptionPurpose purpose) {
    return refusesLegacy(purpose);
  }

  /**
   * Reports that a run with the keyed format open found {@code unbound} values of {@code purpose}
   * in an unbound form; none twice, a confirmation interval apart, retires the purpose.
   */
  void measured(EncryptionPurpose purpose, int unbound);
}
