package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AuditEventType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds {@link AuditEventCatalog#SCHEMA_VERSION} to the catalog: {@code
 * audit/event-catalog/v<N>.json} records the keys of every event type under version N, {@code
 * v<N>-derived.json} those of {@link AuditPayloadKeys}, checked with the real beans in {@code
 * DataCollectionDisclosureIntegrationTest}. A key missing from the catalog fails until the version
 * is raised and its snapshot added; a new key fails until the current snapshot records it. The
 * tests compare the catalog with the current snapshot only: that nobody deletes a key from a
 * snapshot to make them pass is left to the review.
 */
class AuditEventCatalogSnapshotTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void everyKeyOfTheCurrentVersionIsStillListed() throws IOException {
    Map<String, Set<String>> snapshot = snapshot(AuditEventCatalog.SCHEMA_VERSION);
    List<String> gone = new ArrayList<>();
    snapshot.forEach(
        (type, keys) -> {
          Set<String> listed = catalog().getOrDefault(type, Set.of());
          keys.stream()
              .filter(key -> !listed.contains(key))
              .forEach(key -> gone.add(type + "." + key));
        });

    assertThat(gone)
        .as(
            "keys of schema version %s no longer listed; raise SCHEMA_VERSION and add its snapshot",
            AuditEventCatalog.SCHEMA_VERSION)
        .isEmpty();
  }

  @Test
  void theSnapshotOfTheCurrentVersionRecordsEveryListedKey() throws IOException {
    Map<String, Set<String>> snapshot = snapshot(AuditEventCatalog.SCHEMA_VERSION);
    List<String> unrecorded = new ArrayList<>();
    catalog()
        .forEach(
            (type, keys) -> {
              Set<String> recorded = snapshot.getOrDefault(type, Set.of());
              if (!snapshot.containsKey(type)) {
                unrecorded.add(type);
              }
              keys.stream()
                  .filter(key -> !recorded.contains(key))
                  .forEach(key -> unrecorded.add(type + "." + key));
            });

    assertThat(unrecorded)
        .as(
            "listed but not in audit/event-catalog/v%s.json; adding needs no new version",
            AuditEventCatalog.SCHEMA_VERSION)
        .isEmpty();
  }

  @Test
  void everyVersionUpToTheCurrentOneHasItsSnapshot() throws IOException {
    for (int version = 1; version <= AuditEventCatalog.SCHEMA_VERSION; version++) {
      assertThat(snapshot(version)).as("snapshot of version %s", version).isNotEmpty();
    }
  }

  private static Map<String, Set<String>> catalog() {
    Map<String, Set<String>> catalog = new TreeMap<>();
    for (AuditEventType type : AuditEventType.values()) {
      catalog.put(
          type.name(), new TreeSet<>(AuditEventCatalog.PAYLOAD_KEYS.getOrDefault(type, Set.of())));
    }
    return catalog;
  }

  private static Map<String, Set<String>> snapshot(int version) throws IOException {
    return snapshot("v" + version);
  }

  /** {@code audit/event-catalog/<name>.json}, such as {@code v1} or {@code v1-derived}. */
  static Map<String, Set<String>> snapshot(String name) throws IOException {
    String resource = "/audit/event-catalog/" + name + ".json";
    try (InputStream in = AuditEventCatalogSnapshotTest.class.getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      return JSON.readValue(in, new TypeReference<TreeMap<String, Set<String>>>() {});
    }
  }
}
