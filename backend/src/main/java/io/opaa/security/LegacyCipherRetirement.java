package io.opaa.security;

import java.time.Duration;

/**
 * Whether the unbound forms of a purpose - {@code enc:v1:} and, for {@link CredentialsEncryptor},
 * legacy cleartext - are refused (ADR-0064, Entscheidung 6). {@link CiphertextRewrap} reports each
 * keyed run's count of such values; once two runs at least {@link #CONFIRMATION} apart found none
 * and none in between found any, the purpose is retired: from then on such a value found in a
 * column is refused instead of read, and new values are always written keyed.
 */
public interface LegacyCipherRetirement {

  /**
   * How long a purpose stays without unbound values before it is retired: longer than an instance
   * keeps the answer of {@link CipherFormatGate} and than a transaction that wrote such a value
   * before the measurement takes to commit.
   */
  Duration CONFIRMATION = Duration.ofMinutes(5);

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
   * Reports that a run with the keyed format open found {@code unbound} values of {@code purpose}
   * in an unbound form; none twice, {@link #CONFIRMATION} apart, retires the purpose.
   */
  void measured(EncryptionPurpose purpose, int unbound);
}
