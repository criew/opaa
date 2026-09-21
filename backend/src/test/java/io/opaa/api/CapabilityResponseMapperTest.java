package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.CapabilityGrantResponse;
import io.opaa.api.dto.CapabilityOverviewResponse;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.permission.CapabilityGrant;
import io.opaa.permission.CapabilityGrantView;
import io.opaa.permission.CapabilityOverview;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The field-by-field mapping and the four shapes of the plain-text line (AGENTS.md: a response that
 * is more than its entity needs its own mapper test, otherwise nothing holds the mapper to filling
 * every field). No Spring context - the mapper is a pure function of its inputs.
 *
 * <p><b>One field this cannot prove here:</b> {@code createdAt} is written by {@code @PrePersist},
 * so it is {@code null} on an entity that was never saved and both sides of its comparison below
 * agree trivially. That it arrives filled is asserted against the real schema in {@code
 * io.opaa.api.CapabilityEnforcementIntegrationTest}.
 */
class CapabilityResponseMapperTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  @Test
  void carriesEveryFieldOfAGrantIntoTheResponse() {
    UUID subject = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    CapabilityGrant grant =
        CapabilityGrant.forUser(ORGANIZATION, Capability.CREATE_SPACE, subject, actor);

    CapabilityGrantResponse response =
        CapabilityResponseMapper.toResponse(new CapabilityGrantView(grant, "Frau Meier"));

    assertThat(response.getId()).isEqualTo(grant.getId());
    assertThat(response.getCapability()).isEqualTo(Capability.CREATE_SPACE);
    assertThat(response.getSubjectType()).isEqualTo(CapabilitySubjectType.USER);
    assertThat(response.getSubjectId()).isEqualTo(subject);
    assertThat(response.getSubjectName()).isEqualTo("Frau Meier");
    assertThat(response.getGrantedByUserId()).isEqualTo(actor);
    assertThat(response.getCreatedAt()).isEqualTo(grant.getCreatedAt());
  }

  /** {@code ALL_ACCOUNTS} names nobody, and the delivered rows carry no conferring actor. */
  @Test
  void leavesSubjectAndActorEmptyForAGrantToAllAccounts() {
    CapabilityGrant grant =
        CapabilityGrant.forAllAccounts(ORGANIZATION, Capability.CREATE_LIBRARY, null);

    CapabilityGrantResponse response =
        CapabilityResponseMapper.toResponse(new CapabilityGrantView(grant, null));

    assertThat(response.getSubjectType()).isEqualTo(CapabilitySubjectType.ALL_ACCOUNTS);
    assertThat(response.getSubjectId()).isNull();
    assertThat(response.getSubjectName()).isNull();
    assertThat(response.getGrantedByUserId()).isNull();
  }

  @Test
  void statesTheDeliveredStateAsOneLine() {
    assertThat(statementOf(Capability.CREATE_CONNECTOR_LIBRARY, allAccounts()))
        .isEqualTo("Alle Konten dürfen Konnektorbibliotheken anlegen.");
  }

  @Test
  void statesThatOnlyTheSystemAdministrationMayWhenNobodyHoldsIt() {
    assertThat(statementOf(Capability.CREATE_SPACE, List.of()))
        .isEqualTo("Nur die Systemverwaltung darf Spaces anlegen.");
  }

  @Test
  void namesUpToThreeSubjectsAndCountsTheRest() {
    assertThat(statementOf(Capability.CREATE_SPACE, List.of(group("Referat 50"))))
        .isEqualTo("Gruppe Referat 50 sowie die Systemverwaltung dürfen Spaces anlegen.");
    assertThat(
            statementOf(
                Capability.CREATE_SPACE,
                List.of(
                    group("Referat 50"),
                    group("Referat 51"),
                    group("Referat 52"),
                    group("Referat 53"),
                    user("Frau Meier"))))
        .as("three named subjects, the rest counted - the line stays a line")
        .isEqualTo(
            "Gruppe Referat 50, Gruppe Referat 51, Gruppe Referat 52 und 2 weitere sowie die"
                + " Systemverwaltung dürfen Spaces anlegen.");
  }

  /** A UUID in a sentence meant to be read tells a reader nothing; the kind alone does. */
  @Test
  void namesASubjectWhoseNameNoLongerResolvesByItsKindRatherThanByItsId() {
    CapabilityGrantView namelessUser = user(null);
    CapabilityGrantView namelessGroup = group(null);

    String statement = statementOf(Capability.CREATE_SPACE, List.of(namelessUser, namelessGroup));

    assertThat(statement)
        .isEqualTo(
            "Ein Konto ohne auflösbaren Namen, Eine Gruppe ohne auflösbaren Namen sowie die"
                + " Systemverwaltung dürfen Spaces anlegen.");
    assertThat(statement)
        .doesNotContain(namelessUser.grant().getSubjectId().toString())
        .doesNotContain(namelessGroup.grant().getSubjectId().toString());
  }

  /**
   * Since #1814 the capability has a creation path outside the system administration, so its line
   * reads like every other one - no capability is qualified any more.
   */
  @Test
  void statesTheInternalGroupCapabilityWithoutAnyReservation() {
    assertThat(statementOf(Capability.CREATE_INTERNAL_GROUP, List.of(group("Referat 50"))))
        .isEqualTo("Gruppe Referat 50 sowie die Systemverwaltung dürfen interne Gruppen anlegen.");
    assertThat(statementOf(Capability.CREATE_INTERNAL_GROUP, List.of()))
        .isEqualTo("Nur die Systemverwaltung darf interne Gruppen anlegen.");
  }

  @Test
  void listsTheCallersOwnCapabilitiesInTheOrderOfTheEnum() {
    assertThat(
            CapabilityResponseMapper.toMyCapabilities(
                    Set.of(Capability.CREATE_LIBRARY, Capability.CREATE_SPACE))
                .getCapabilities())
        .containsExactly(Capability.CREATE_SPACE, Capability.CREATE_LIBRARY);
  }

  private static String statementOf(Capability capability, List<CapabilityGrantView> grants) {
    CapabilityOverviewResponse response =
        CapabilityResponseMapper.toOverviewResponse(new CapabilityOverview(capability, grants));
    assertThat(response.getCapability()).isEqualTo(capability);
    assertThat(response.getLabel()).isNotBlank();
    assertThat(response.getGrants()).hasSize(grants.size());
    return response.getStatement();
  }

  private static List<CapabilityGrantView> allAccounts() {
    return List.of(
        new CapabilityGrantView(
            CapabilityGrant.forAllAccounts(ORGANIZATION, Capability.CREATE_CONNECTOR_LIBRARY, null),
            null));
  }

  private static CapabilityGrantView group(String name) {
    return new CapabilityGrantView(
        CapabilityGrant.forGroup(
            ORGANIZATION, Capability.CREATE_SPACE, UUID.randomUUID(), UUID.randomUUID()),
        name);
  }

  private static CapabilityGrantView user(String name) {
    return new CapabilityGrantView(
        CapabilityGrant.forUser(
            ORGANIZATION, Capability.CREATE_SPACE, UUID.randomUUID(), UUID.randomUUID()),
        name);
  }
}
