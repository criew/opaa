package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ConfigurationGuard.Impact;
import io.opaa.compliance.ConfigurationGuard.Key;
import io.opaa.compliance.FingerprintKeys.FingerprintKey;
import io.opaa.compliance.FingerprintKeys.MetadataKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The structure test of the configuration fingerprint (ADR-0053, Entscheidung 4): every {@code
 * opaa.*} key either bears on data, and so enters the fingerprint through the manifest, or is
 * marked installation-wide or per instance where it is bound. A new key fails here until it is.
 */
class FingerprintKeysTest {

  private static final List<Key> METADATA = ConfigurationGuard.deliveredMetadata();
  private static final Map<String, FingerprintKey> FINGERPRINT =
      FingerprintKeys.delivered().stream()
          .collect(Collectors.toMap(FingerprintKey::name, Function.identity()));

  @Test
  void decidesForEveryKeyWithoutDataImpactWhetherItMayDifferBetweenInstances() {
    List<String> gaps = new ArrayList<>();
    for (Key key : METADATA) {
      if (!key.name().startsWith("opaa.")
          || ConfigurationGuard.impactOf(key, new ArrayList<>()).orElse(null) != Impact.NONE) {
        continue;
      }
      FingerprintKeys.scopeOf(new MetadataKey(key.name(), key.sourceType()), gaps);
    }

    assertThat(gaps)
        .withFailMessage(
            () ->
                gaps.size()
                    + " key(s) without an instance scope (ADR-0053, Entscheidung 4):\n\n"
                    + String.join("\n\n", gaps))
        .isEmpty();
  }

  @Test
  void holdsEveryKeyWithDataImpact() {
    List<String> withImpact =
        METADATA.stream()
            .filter(key -> key.name().startsWith("opaa."))
            .filter(
                key ->
                    ConfigurationGuard.impactOf(key, new ArrayList<>()).orElse(null) == Impact.DATA)
            .map(Key::name)
            .toList();

    assertThat(withImpact).isNotEmpty();
    assertThat(FINGERPRINT.keySet()).containsAll(withImpact);
  }

  @Test
  void holdsTheInstallationWideTechnicalValuesOfTheAdr() {
    assertThat(FINGERPRINT.keySet())
        .contains(
            "opaa.auth.local.jwt-secret",
            "opaa.security.credentials.encryption-key",
            "opaa.security.settings.encryption-key",
            "spring.ai.vectorstore.pgvector.distance-type",
            "opaa.indexing.chunk-size",
            "opaa.indexing.chunk-overlap",
            "opaa.database.schema",
            "opaa.upload.store",
            "opaa.upload.storage-path",
            "opaa.upload.s3.bucket",
            "opaa.indexing.filesystem.allowlist",
            "opaa.indexing.target-validation.allowlist",
            "opaa.rate-limit.trusted-proxy-cidrs",
            "opaa.public-base-url",
            "opaa.features.external-access.enabled",
            "opaa.instance.dead-after");
  }

  @Test
  void leavesBoundsOfTheOneProcessOut() {
    assertThat(FINGERPRINT.keySet())
        .doesNotContain("opaa.instance.heartbeat-interval", "opaa.indexing.thread-pool.core-size");
  }

  @Test
  void neverNamesTheValueOfASecret() {
    assertThat(FINGERPRINT.get("opaa.auth.local.jwt-secret").secret()).isTrue();
    assertThat(FINGERPRINT.get("opaa.security.credentials.encryption-key").secret()).isTrue();
    assertThat(FINGERPRINT.get("opaa.security.settings.encryption-key").secret()).isTrue();
    assertThat(FINGERPRINT.get("opaa.database.schema").secret()).isFalse();
  }

  @Test
  void takesAKeyNamingASecretForOneEvenWithoutTheFlag() {
    assertThat(FingerprintKeys.looksSecret("opaa.upload.s3.secret-access-key")).isTrue();
    assertThat(FingerprintKeys.looksSecret("opaa.mail.password")).isTrue();
    assertThat(FingerprintKeys.looksSecret("opaa.database.schema")).isFalse();
    assertThat(FingerprintKeys.looksSecret("opaa.indexing.chunk-size")).isFalse();
  }
}
