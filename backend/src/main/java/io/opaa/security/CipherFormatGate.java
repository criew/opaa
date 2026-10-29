package io.opaa.security;

/**
 * Whether new ciphertexts may be written in the keyed format {@code enc:v2:}. A version that reads
 * only {@code enc:v1:} may still run next to this one during a rolling update (ADR-0053,
 * Entscheidung 13); until none does, writes stay in {@code enc:v1:} under the current key, and
 * {@link CiphertextRewrap} converts them later.
 */
@FunctionalInterface
public interface CipherFormatGate {

  /** Always the keyed format; for tests and tools without an instance register. */
  CipherFormatGate KEYED = () -> true;

  boolean writesKeyedFormat();
}
