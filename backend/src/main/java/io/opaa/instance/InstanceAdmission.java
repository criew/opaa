package io.opaa.instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides whether a starting instance may join the live ones (ADR-0053, Entscheidung 4): every live
 * instance must run a version inside its rolling-update window and hold the same value for every
 * fingerprint key both know.
 */
final class InstanceAdmission {

  private InstanceAdmission() {}

  /**
   * A live instance as the starting one sees it.
   *
   * @param fingerprint its fingerprint under the installation's HMAC key
   */
  record LiveInstance(
      UUID id, String hostName, InstanceVersion version, ConfigurationFingerprint fingerprint) {}

  /**
   * Throws when the candidate may not start next to {@code live}, naming every live instance it
   * does not fit and, per instance, the keys that differ.
   *
   * @throws InstanceStartRefusedException naming the version or the differing keys
   */
  static void check(
      UUID candidateId,
      InstanceVersion version,
      ConfigurationFingerprint fingerprint,
      List<FingerprintValue> values,
      List<LiveInstance> live) {
    Map<String, FingerprintValue> byKey =
        values.stream().collect(Collectors.toMap(FingerprintValue::key, Function.identity()));
    List<String> reasons = new ArrayList<>();
    for (LiveInstance other : live) {
      if (!version.compatibleWith(other.version())) {
        reasons.add(
            "version "
                + version.version()
                + " may not run next to version "
                + other.version().version()
                + " of live instance "
                + describe(other)
                + " ("
                + window(version, other.version())
                + ")");
        continue;
      }
      List<String> differing = fingerprint.differingKeys(other.fingerprint());
      if (!differing.isEmpty()) {
        reasons.add(
            "differs from live instance "
                + describe(other)
                + " in "
                + differing.stream()
                    .map(key -> byKey.get(key).describe())
                    .collect(Collectors.joining(", ")));
      }
    }
    if (!reasons.isEmpty()) {
      throw new InstanceStartRefusedException(
          "Instance "
              + candidateId
              + " refuses to start: "
              + String.join("; ", reasons)
              + ". Every instance of an installation must hold the same value of these keys;"
              + " change one only by restarting all instances (ADR-0053, Entscheidung 4).");
    }
  }

  private static String describe(LiveInstance instance) {
    return instance.id() + " on " + instance.hostName();
  }

  private static String window(InstanceVersion a, InstanceVersion b) {
    InstanceVersion newer = InstanceVersion.compare(a.version(), b.version()) > 0 ? a : b;
    return "version " + newer.version() + " runs next to versions from " + newer.oldestCompatible();
  }
}
