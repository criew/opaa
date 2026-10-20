package io.opaa.instance;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The configuration fingerprint of one instance (ADR-0053, Entscheidung 4): per key an HMAC of its
 * value under the key the installation keeps in the register, never the value itself. Equal values
 * give equal digests on every instance of the installation, so two fingerprints compare without any
 * value leaving its process.
 *
 * @param digests the digest of each key, sorted by key
 */
record ConfigurationFingerprint(SortedMap<String, String> digests) {

  private static final String ALGORITHM = "HmacSHA256";

  /** Stands for an unset value, so that unset and empty differ. */
  private static final String UNSET = "\u0000unset";

  ConfigurationFingerprint {
    digests = new TreeMap<>(Objects.requireNonNull(digests, "digests"));
  }

  /** The fingerprint of {@code values} under {@code hmacKey}. */
  static ConfigurationFingerprint of(Collection<FingerprintValue> values, byte[] hmacKey) {
    SortedMap<String, String> digests = new TreeMap<>();
    for (FingerprintValue value : values) {
      digests.put(value.key(), digest(hmacKey, value.key(), value.value()));
    }
    return new ConfigurationFingerprint(digests);
  }

  /**
   * The keys both fingerprints know whose digests differ, sorted. A key only one side knows is no
   * difference: two versions in a rolling update compare on the keys they share.
   */
  List<String> differingKeys(ConfigurationFingerprint other) {
    return digests.entrySet().stream()
        .filter(entry -> other.digests.containsKey(entry.getKey()))
        .filter(entry -> !other.digests.get(entry.getKey()).equals(entry.getValue()))
        .map(Map.Entry::getKey)
        .toList();
  }

  /** One digest over all keys and digests, to tell two fingerprints apart at a glance. */
  String hash(byte[] hmacKey) {
    StringBuilder all = new StringBuilder();
    digests.forEach((key, digest) -> all.append(key).append('=').append(digest).append('\n'));
    return digest(hmacKey, "", all.toString());
  }

  private static String digest(byte[] hmacKey, String key, String value) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(hmacKey, ALGORITHM));
      byte[] input =
          (key + '\u0000' + (value == null ? UNSET : value)).getBytes(StandardCharsets.UTF_8);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(ALGORITHM + " is not available", e);
    }
  }
}
