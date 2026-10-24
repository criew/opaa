package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AuditEventType;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The positive list of {@code before}/{@code after} keys a self-disclosure repeats. */
class AuditSelfDisclosurePayloadTest {

  private static final String SELF = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER = "22222222-2222-2222-2222-222222222222";

  @Test
  void everyEventTypeHasADecision() {
    assertThat(AuditSelfDisclosurePayload.KEYS.keySet())
        .containsExactlyInAnyOrder(AuditEventType.values());
  }

  /** The event types searched for an owner are exactly those whose list repeats an owner. */
  @Test
  void theOwnerEventTypesAreThoseWhoseListHoldsTheOwner() {
    assertThat(AuditOwnerPayload.EVENT_TYPES)
        .containsExactlyInAnyOrderElementsOf(
            AuditSelfDisclosurePayload.KEYS.entrySet().stream()
                .filter(e -> e.getValue().contains(AuditOwnerPayload.KEY))
                .map(Map.Entry::getKey)
                .toList());
  }

  @Test
  void onlyListedKeysWithPlainValuesGoOut() {
    String stored =
        "{\"role\":\"MEMBER\",\"memberName\":\"Max Mustermann\",\"subjectId\":\""
            + OTHER
            + "\",\"nested\":{\"role\":\"OWNER\"}}";

    assertThat(AuditSelfDisclosurePayload.disclose(AuditEventType.SPACE_MEMBER_ADDED, stored, SELF))
        .isEqualTo("{\"role\":\"MEMBER\"}");
  }

  @Test
  void anOwnerIdGoesOutOnlyAsYouOrAnotherOne() {
    assertThat(
            AuditSelfDisclosurePayload.disclose(
                AuditEventType.ASSET_OWNER_CHANGED, "{\"ownerId\":\"" + SELF + "\"}", SELF))
        .isEqualTo("{\"ownerId\":\"Sie\"}");
    assertThat(
            AuditSelfDisclosurePayload.disclose(
                AuditEventType.ASSET_OWNER_CHANGED, "{\"ownerId\":\"" + OTHER + "\"}", SELF))
        .isEqualTo("{\"ownerId\":\"andere Person oder Gruppe\"}")
        .doesNotContain(OTHER);
  }

  @Test
  void anOwnerIdHoldingThePersonsPseudonymGoesOutAsYou() {
    String pseudonym = "33333333-3333-3333-3333-333333333333";

    assertThat(
            AuditSelfDisclosurePayload.disclose(
                AuditEventType.ASSET_OWNER_CHANGED,
                "{\"ownerId\":\"" + pseudonym + "\"}",
                pseudonym,
                SELF))
        .isEqualTo("{\"ownerId\":\"Sie\"}");
  }

  @Test
  void nothingGoesOutWhereTheListIsEmptyOrThePayloadIsUnreadable() {
    assertThat(
            AuditSelfDisclosurePayload.disclose(
                AuditEventType.GROUP_STEWARD_APPOINTED, "{\"transferId\":\"x\"}", SELF))
        .isNull();
    assertThat(AuditSelfDisclosurePayload.disclose(AuditEventType.SPACE_CHANGED, "{kaputt", SELF))
        .isNull();
    assertThat(AuditSelfDisclosurePayload.disclose(null, "{\"role\":\"X\"}", SELF)).isNull();
  }

  @Test
  void noListNamesAKeyThatCarriesAPersonOrASecret() {
    assertThat(
            AuditSelfDisclosurePayload.KEYS.values().stream()
                .flatMap(keys -> keys.stream())
                .distinct())
        .doesNotContainAnyElementsOf(
            Arrays.asList(
                "targetId",
                "transferId",
                "providerId",
                "username",
                "fromAddress",
                "fromName",
                "clientId",
                "issuerUri",
                "value",
                "displayValue",
                "fileName",
                "documentId",
                "scopeGroupId",
                "profileGroupId",
                "libraryIds",
                "externalId",
                "objectId",
                "scopeId",
                "correlationRef",
                "proxyUrl"));
  }
}
