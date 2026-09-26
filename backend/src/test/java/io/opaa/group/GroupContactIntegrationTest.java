package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.SuccessionFinding;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.ProviderFixtures;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The contact points of provider groups against the real schema (#1875, ADR-0036 Entscheidung 9):
 * the administration names them, they alone decide the protection mark, the group stays read-only,
 * and the appointment ends with the membership it rests on.
 */
@OpaaIntegrationTest
class GroupContactIntegrationTest {

  @Autowired private GroupContactService contactService;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupContactRepository contactRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private GroupSuccessionSource successionSource;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;

  private UUID organizationId;
  private UUID providerId;
  private CurrentUser admin;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org 1875")).getId();
    providerId = ProviderFixtures.tokenProvider(providerRepository).getId();
    admin = currentUserOf(account(SystemRole.SYSTEM_ADMIN));
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM group_contacts WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM group_stewards WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM group_membership_history WHERE organization_id = ?", organizationId);
    groupRepository.deleteAll(
        groupRepository.findAll().stream()
            .filter(group -> group.getOrganizationId().equals(organizationId))
            .toList());
    userRepository.deleteAllById(createdUserIds);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
    if (providerId != null) {
      providerRepository.deleteById(providerId);
      providerId = null;
    }
  }

  // -------------------------------------------------------------------------------------------
  // Naming and dismissing
  // -------------------------------------------------------------------------------------------

  @Test
  void theAdministrationNamesAMemberOfTheGroupAndTheGroupItselfStaysUntouched() {
    UUID member = account(SystemRole.USER);
    Group group = providerGroupWith(member);

    GroupContactView appointed = contactService.appointContact(group.getId(), member, admin);

    assertThat(appointed.contact().getUserId()).isEqualTo(member);
    assertThat(appointed.contact().getAppointedByUserId()).isEqualTo(admin.id());
    assertThat(contactService.contactsOf(group.getId()))
        .extracting(view -> view.contact().getUserId())
        .containsExactly(member);
    assertThat(auditEvents("GROUP_CONTACT_APPOINTED")).isEqualTo(1);
    assertThat(groupRepository.findById(group.getId()).orElseThrow().getUpdatedAt())
        .as("the Verwaltungsakt changes nothing about the group itself")
        .isCloseTo(group.getUpdatedAt(), within(1, ChronoUnit.MILLIS));
  }

  /** The appointment speaks for the body concerned - somebody outside it speaks for nobody. */
  @Test
  void onlyAMemberOfTheGroupCanBeItsContactPoint() {
    Group group = providerGroupWith(account(SystemRole.USER));
    UUID stranger = account(SystemRole.USER);

    assertThatThrownBy(() -> contactService.appointContact(group.getId(), stranger, admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Mitglied dieser Gruppe");
  }

  /**
   * An internal group decides its protection through its stewards; a contact point is no second
   * way.
   */
  @Test
  void anInternalGroupTakesNoContactPoint() {
    UUID member = account(SystemRole.USER);
    Group internal =
        groupRepository.save(Group.internal(organizationId, "Projektteam", null, null));
    internal.addMembership(new GroupMembership(member, organizationId));
    groupRepository.save(internal);

    assertThatThrownBy(() -> contactService.appointContact(internal.getId(), member, admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Verantwortliche");
  }

  @Test
  void thePersonIsNamedOnlyOnceAndDismissedWithAnAuditEvent() {
    UUID member = account(SystemRole.USER);
    Group group = providerGroupWith(member);
    contactService.appointContact(group.getId(), member, admin);

    assertThatThrownBy(() -> contactService.appointContact(group.getId(), member, admin))
        .isInstanceOf(ConflictException.class);

    contactService.dismissContact(group.getId(), member, admin);

    assertThat(contactRepository.findByGroupIdOrderByCreatedAtAsc(group.getId())).isEmpty();
    assertThat(auditEvents("GROUP_CONTACT_DISMISSED")).isEqualTo(1);
    assertThatThrownBy(() -> contactService.dismissContact(group.getId(), member, admin))
        .isInstanceOf(NotFoundException.class);
  }

  /**
   * Naming is an administrative act, so the two paths carry the role barrier at the door - and the
   * administration gets past it into the service's own answer, not merely past a {@code 403}.
   */
  @Test
  void theTwoAdminPathsAreClosedToEverybodyButTheAdministration() throws Exception {
    String unknownGroup = UUID.randomUUID().toString();
    String body = "{\"userId\":\"" + UUID.randomUUID() + "\"}";

    mockMvc
        .perform(
            post("/api/v1/admin/groups/" + unknownGroup + "/contacts")
                .with(devUser())
                .content(body))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            delete("/api/v1/admin/groups/" + unknownGroup + "/contacts/" + UUID.randomUUID())
                .with(devUser()))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/v1/admin/groups/" + unknownGroup + "/contacts")
                .with(devAdmin())
                .content(body))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------------------------
  // The protection mark
  // -------------------------------------------------------------------------------------------

  /**
   * The acceptance criterion of the issue: the contact point sets and releases the mark, and the
   * administration - which named them - cannot.
   */
  @Test
  void onlyTheContactPointSetsAndReleasesTheMarkOfAProviderGroup() {
    UUID memberId = account(SystemRole.USER);
    Group group = providerGroupWith(memberId);
    CurrentUser member = currentUserOf(memberId);

    assertThatThrownBy(() -> groupService.setProtection(group.getId(), true, admin))
        .as("the administration decides the protection of no group")
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Ansprechstellen");
    assertThatThrownBy(() -> groupService.setProtection(group.getId(), true, member))
        .as("a mere member is not the contact point either")
        .isInstanceOf(AccessDeniedException.class);

    contactService.appointContact(group.getId(), memberId, admin);

    assertThat(groupService.setProtection(group.getId(), true, member).group().isProtectedGroup())
        .isTrue();
    assertThat(groupService.setProtection(group.getId(), false, member).group().isProtectedGroup())
        .isFalse();
    assertThat(auditEvents("GROUP_PROTECTION_CHANGED")).isEqualTo(2);
  }

  /** A caller with no relation to the group learns nothing about its existence. */
  @Test
  void aStrangerGetsTheAnswerOfAnUnknownGroup() {
    Group group = providerGroupWith(account(SystemRole.USER));
    CurrentUser stranger = currentUserOf(account(SystemRole.USER));

    assertThatThrownBy(() -> groupService.setProtection(group.getId(), true, stranger))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Gruppe nicht gefunden");
  }

  /**
   * Being a contact point is no maintenance right: the group stays maintained where it comes from.
   */
  @Test
  void aContactPointCannotMaintainTheGroup() {
    UUID memberId = account(SystemRole.USER);
    Group group = providerGroupWith(memberId);
    contactService.appointContact(group.getId(), memberId, admin);
    CurrentUser contact = currentUserOf(memberId);

    assertThatThrownBy(
            () -> groupService.updateGroup(group.getId(), new GroupUpdate("Anders", null), contact))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () -> groupService.addMember(group.getId(), account(SystemRole.USER), contact))
        .isInstanceOf(NotFoundException.class);
    assertThat(groupService.getGroup(group.getId(), contact).group().getId())
        .as("they do read their group - they decide its mark and have to see it")
        .isEqualTo(group.getId());
    assertThat(groupService.getGroup(group.getId(), contact).members())
        .as("the list is withheld from the administration only, not from the contact point")
        .extracting(view -> view.membership().getUserId())
        .containsExactly(memberId);
    assertThat(groupService.listContactedGroups(contact))
        .extracting(overview -> overview.group().getId())
        .containsExactly(group.getId());
  }

  // -------------------------------------------------------------------------------------------
  // What the grant giver sees, and the lifecycle
  // -------------------------------------------------------------------------------------------

  /** ADR-0036, Entscheidung 9: at a protected group the grant giver sees the contact point. */
  @Test
  void theGrantGiverSeesTheContactPointInsteadOfTheMemberList() {
    UUID memberId = account(SystemRole.USER, "Andrea Vogt");
    Group group = providerGroupWith(memberId);
    contactService.appointContact(group.getId(), memberId, admin);
    groupService.setProtection(group.getId(), true, currentUserOf(memberId));

    SelectableGroup selectable =
        groupService.resolveSelectableGroup(group.getId(), admin).orElseThrow();

    assertThat(selectable.responsible()).containsExactly("Andrea Vogt");
    assertThat(selectable.activeMemberCount())
        .as("the size stays withheld at a protected group - that is the actual disclosure")
        .isNull();
  }

  /** The appointment rests on the membership and ends with it, recorded by the process. */
  @Test
  void leavingTheGroupEndsTheAppointment() {
    UUID memberId = account(SystemRole.USER);
    Group group = providerGroupWith(memberId);
    contactService.appointContact(group.getId(), memberId, admin);

    Group reloaded = groupRepository.findByIdWithMemberships(group.getId()).orElseThrow();
    reloaded.removeMembership(
        reloaded.getMemberships().stream()
            .filter(membership -> membership.getUserId().equals(memberId))
            .findFirst()
            .orElseThrow());
    groupRepository.save(reloaded);
    membershipResolver.invalidateUser(memberId);

    assertThat(contactRepository.findByGroupIdOrderByCreatedAtAsc(group.getId())).isEmpty();
    assertThat(auditEvents("GROUP_CONTACT_DISMISSED"))
        .as("the end is on the record, with no acting person")
        .isEqualTo(1);
  }

  /**
   * A protected provider group whose contact point cannot act is frozen: nobody may lift the mark,
   * and the administration must not do it for them - so it belongs in the operational list (#1819).
   */
  @Test
  void aProtectedProviderGroupWithoutAUsableContactPointIsAnOpenSuccession() {
    UUID memberId = account(SystemRole.USER);
    Group group = providerGroupWith(memberId);
    contactService.appointContact(group.getId(), memberId, admin);
    groupService.setProtection(group.getId(), true, currentUserOf(memberId));

    assertThat(successionSource.findingFor(group.getId())).isEmpty();

    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", memberId);

    assertThat(successionSource.findingFor(group.getId()))
        .get()
        .extracting(SuccessionFinding::objectName, SuccessionFinding::objectType)
        .containsExactly("Geschützte Gruppe", SuccessionObjectType.GROUP);
    assertThat(successionSource.kind()).isEqualTo(SuccessionKind.OPEN_SUCCESSION);
    assertThat(successionSource.findingsOf(organizationId))
        .extracting(SuccessionFinding::objectId)
        .contains(group.getId());
  }

  /**
   * Who may act follows the origin, at <b>both</b> entry points of the source: a steward of a
   * provider group - which only the stock of #1814 has - can no longer touch its mark, so their
   * being active must not hide the frozen group from the list.
   */
  @Test
  void anActiveStewardOfAProviderGroupHidesNothingFromTheList() {
    UUID contactId = account(SystemRole.USER);
    UUID stewardId = account(SystemRole.USER);
    Group group = providerGroupWith(contactId);
    stewardRepository.save(new GroupSteward(group.getId(), stewardId, organizationId, admin.id()));
    contactService.appointContact(group.getId(), contactId, admin);
    groupService.setProtection(group.getId(), true, currentUserOf(contactId));

    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", contactId);

    assertThat(successionSource.findingFor(group.getId()))
        .as("the single derivation already counted the contact points alone")
        .isPresent();
    assertThat(successionSource.findingsOf(organizationId))
        .as("and the list path must agree - the steward cannot lift this mark")
        .extracting(SuccessionFinding::objectId)
        .contains(group.getId());
  }

  /** An unprotected provider group needs no contact point and is no entry of that list. */
  @Test
  void anUnprotectedProviderGroupWithoutAContactPointIsNoEntry() {
    Group group = providerGroupWith(account(SystemRole.USER));

    assertThat(successionSource.findingFor(group.getId())).isEmpty();
    assertThat(successionSource.findingsOf(organizationId))
        .extracting(SuccessionFinding::objectId)
        .doesNotContain(group.getId());
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private Group providerGroupWith(UUID memberId) {
    Group group =
        new Group(
            organizationId,
            GroupKind.IDENTITY_PROVIDER,
            "Referat " + UUID.randomUUID(),
            null,
            providerId,
            "Referat 50",
            null,
            null);
    group.addMembership(new GroupMembership(memberId, organizationId));
    Group saved = groupRepository.save(group);
    membershipResolver.invalidateUser(memberId);
    return saved;
  }

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private long auditEvents(String type) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE organization_id = ? AND event_type = ?",
        Long.class,
        organizationId,
        type);
  }

  private UUID account(SystemRole role) {
    return account(role, "Test User");
  }

  private UUID account(SystemRole role, String displayName) {
    User user =
        new User(
            UUID.randomUUID().toString(),
            "test-issuer",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private CurrentUser currentUserOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        user.getSystemRole(),
        user.getDisplayName(),
        user.getEmail());
  }
}
