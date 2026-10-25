package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AuditEventType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The event contract: the payload keys every event type may carry. */
class AuditEventCatalogTest {

  @Test
  void everyEventTypeHasAnEntry() {
    assertThat(AuditEventCatalog.PAYLOAD_KEYS.keySet())
        .containsExactlyInAnyOrder(AuditEventType.values());
  }

  @Test
  void theSelfDisclosureRepeatsOnlyListedKeys() {
    Map<AuditEventType, Set<String>> beyondTheCatalog = new TreeMap<>();
    AuditSelfDisclosurePayload.KEYS.forEach(
        (type, keys) -> {
          Set<String> beyond = new TreeSet<>(keys);
          beyond.removeAll(AuditEventCatalog.PAYLOAD_KEYS.get(type));
          if (!beyond.isEmpty()) {
            beyondTheCatalog.put(type, beyond);
          }
        });

    assertThat(beyondTheCatalog).isEmpty();
  }

  @Test
  void unlistedKeysOfBothSidesAreReported() {
    assertThat(
            AuditEventCatalog.unlistedKeys(
                Set.of("role"), "{\"role\":\"MEMBER\",\"b\":1}", "{\"a\":null,\"role\":\"X\"}"))
        .containsExactly("a", "b");
    assertThat(AuditEventCatalog.unlistedKeys(Set.of("role"), null, "{\"role\":null}")).isEmpty();
  }

  @Test
  void aSideThatIsNoJsonObjectIsReported() {
    for (String payload : List.of("[\"x\"]", "\"text\"", "{kaputt", "")) {
      assertThat(AuditEventCatalog.unlistedKeys(Set.of("role"), payload, null))
          .as(payload)
          .containsExactly(AuditEventCatalog.NOT_AN_OBJECT);
    }
  }

  @Test
  void listedOnlyKeepsTheListedKeysInTheirOrderWithTheirValues() {
    assertThat(
            AuditEventCatalog.listedOnly(
                Set.of("role", "expiresAt"),
                "{\"expiresAt\":null,\"name\":\"Erika\",\"role\":{\"a\":[1]}}"))
        .isEqualTo("{\"expiresAt\":null,\"role\":{\"a\":[1]}}");
    assertThat(AuditEventCatalog.listedOnly(Set.of("role"), "{\"name\":\"Erika\"}")).isNull();
    assertThat(AuditEventCatalog.listedOnly(Set.of("role"), "[1]")).isNull();
    assertThat(AuditEventCatalog.listedOnly(Set.of("role"), null)).isNull();
  }

  @Test
  void derivedKeysCountOnlyForTheirOwnType() {
    AuditPayloadKeys derived =
        new AuditPayloadKeys() {
          @Override
          public AuditEventType eventType() {
            return AuditEventType.BRANDING_SETTINGS_CHANGED;
          }

          @Override
          public Set<String> keys() {
            return Set.of("logoVersion");
          }
        };

    assertThat(
            AuditEventCatalog.listedKeys(
                AuditEventType.BRANDING_SETTINGS_CHANGED, List.of(derived)))
        .contains("productName", "logoVersion");
    assertThat(AuditEventCatalog.listedKeys(AuditEventType.SPACE_CHANGED, List.of(derived)))
        .doesNotContain("logoVersion");
  }
}
