package io.opaa.instance;

import io.opaa.compliance.InstanceDisclosureAssembler;
import java.util.Objects;

/**
 * One value of the configuration fingerprint as this instance holds it. It never leaves the
 * process: the register stores only its digest.
 *
 * @param value the effective value; {@code null} when the key is unset
 * @param secret whether {@link #describe()} may name only the key
 */
record FingerprintValue(String key, String value, boolean secret) {

  FingerprintValue {
    Objects.requireNonNull(key, "key");
  }

  /** The key, and for a value that is no secret the value of this instance in exportable form. */
  String describe() {
    if (secret) {
      return key;
    }
    return key
        + " (this instance: "
        + (value == null ? "unset" : InstanceDisclosureAssembler.exportable(value))
        + ")";
  }
}
