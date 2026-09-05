package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link TokenGroupSynchronizer} against a real Postgres (#1331, ADR-0025 Entscheidung 4): a
 * sign-in's groups claim becomes {@link GroupKind#IDENTITY_PROVIDER} memberships in the provider's
 * namespace, same-named groups of two providers stay two groups, a token that drops a group ends
 * the membership, an unchanged token writes nothing, and the group management refuses to touch such
 * a group.
 */
@OpaaIntegrationTest
class TokenGroupSynchronizerIntegrationTest {

  @Autowired private TokenGroupSynchronizer synchronizer;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private User alice;
  private OidcProvider beschaeftigte;
  private OidcProvider partner;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Token Groups")).getId();
    alice =
        new User("sub-" + UUID.randomUUID(), "https://idp.example/realms/a", "a@x.example", "A");
    alice.setOrganizationId(organizationId);
    alice = userRepository.save(alice);
    OidcClaimMapping withGroups = new OidcClaimMapping(null, null, null, null, null, "groups");
    beschaeftigte =
        new OidcProvider(
            "Beschäftigte", "https://idp.example/realms/a", "opaa-frontend", null, withGroups);
    partner =
        new OidcProvider(
            "Partner", "https://partner.example/realms/b", "opaa-frontend", null, withGroups);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM group_membership_history WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM group_memberships WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM groups WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM spaces WHERE owner_id = ?", alice.getId());
    userRepository.deleteById(alice.getId());
    organizationRepository.deleteById(organizationId);
  }

  private List<Group> tokenGroups() {
    return groupRepository.findByOrganizationId(organizationId).stream()
        .filter(g -> g.getKind() == GroupKind.IDENTITY_PROVIDER)
        .toList();
  }

  @Test
  void theTokensGroupsBecomeNamespacedMembershipsAndFollowTheToken() {
    synchronizer.apply(alice, beschaeftigte, List.of("Fachbereich 3", "Projekt Phoenix"));

    List<Group> groups = tokenGroups();
    assertThat(groups)
        .extracting(Group::getExternalId)
        .containsExactlyInAnyOrder(
            "oidc:" + beschaeftigte.getId() + ":Fachbereich 3",
            "oidc:" + beschaeftigte.getId() + ":Projekt Phoenix");
    assertThat(groups)
        .extracting(Group::getName)
        .containsExactlyInAnyOrder("Fachbereich 3", "Projekt Phoenix");
    assertThat(membershipResolver.groupIdsForUser(alice.getId()))
        .containsExactlyInAnyOrderElementsOf(groups.stream().map(Group::getId).toList());

    // the next token no longer names Projekt Phoenix: the membership ends, the group stays
    synchronizer.apply(alice, beschaeftigte, List.of("Fachbereich 3"));

    assertThat(membershipResolver.groupIdsForUser(alice.getId()))
        .containsExactly(
            groups.stream()
                .filter(g -> g.getName().equals("Fachbereich 3"))
                .findFirst()
                .orElseThrow()
                .getId());
    assertThat(tokenGroups()).hasSize(2);
    List<Map<String, Object>> history =
        jdbcTemplate.queryForList(
            "SELECT cause FROM group_membership_history WHERE user_id = ? ORDER BY created_at",
            alice.getId());
    assertThat(history)
        .extracting(row -> row.get("cause"))
        .containsExactly(
            "IDENTITY_PROVIDER_ADDED", "IDENTITY_PROVIDER_ADDED", "IDENTITY_PROVIDER_REMOVED");
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT event_type FROM audit_log WHERE organization_id = ? ORDER BY recorded_at,"
                    + " event_id",
                organizationId))
        .extracting(row -> row.get("event_type"))
        .containsExactly(
            "GROUP_CREATED",
            "GROUP_MEMBER_ADDED",
            "GROUP_CREATED",
            "GROUP_MEMBER_ADDED",
            "GROUP_MEMBER_REMOVED");
  }

  @Test
  void sameNamedGroupsOfTwoProvidersAreTwoGroups() {
    synchronizer.apply(alice, beschaeftigte, List.of("Fachbereich 3"));
    User bob = new User("sub-" + UUID.randomUUID(), partner.getIssuerUri(), "b@x.example", "B");
    bob.setOrganizationId(organizationId);
    bob = userRepository.save(bob);
    try {
      synchronizer.apply(bob, partner, List.of("Fachbereich 3"));

      List<Group> groups = tokenGroups();
      assertThat(groups).hasSize(2);
      assertThat(groups).extracting(Group::getName).containsOnly("Fachbereich 3");
      assertThat(membershipResolver.groupIdsForUser(alice.getId()))
          .doesNotContainAnyElementsOf(membershipResolver.groupIdsForUser(bob.getId()));
      // the partner's next token without the group touches only the partner's namespace
      synchronizer.apply(bob, partner, List.of());
      assertThat(membershipResolver.groupIdsForUser(alice.getId())).hasSize(1);
      assertThat(membershipResolver.groupIdsForUser(bob.getId())).isEmpty();
    } finally {
      jdbcTemplate.update("DELETE FROM group_membership_history WHERE user_id = ?", bob.getId());
      jdbcTemplate.update("DELETE FROM group_memberships WHERE user_id = ?", bob.getId());
      jdbcTemplate.update("DELETE FROM spaces WHERE owner_id = ?", bob.getId());
      userRepository.deleteById(bob.getId());
    }
  }

  @Test
  void anUnchangedTokenWritesNothingAndAnOverlongNameIsSkipped() {
    String overlong = "x".repeat(TokenGroupSynchronizer.MAX_NAME_LENGTH + 1);
    synchronizer.apply(alice, beschaeftigte, List.of("Fachbereich 3", overlong));
    int historyRows = historyRowsOfAlice();
    assertThat(tokenGroups()).hasSize(1);

    synchronizer.apply(alice, beschaeftigte, List.of("Fachbereich 3", overlong));

    assertThat(historyRowsOfAlice()).isEqualTo(historyRows);
  }

  @Test
  void theGroupManagementRefusesToEditATokenGroup() {
    synchronizer.apply(alice, beschaeftigte, List.of("Fachbereich 3"));
    Group group = tokenGroups().getFirst();
    CurrentUser admin =
        CurrentUser.of(alice.getId(), organizationId, SystemRole.SYSTEM_ADMIN, "A", "a@x.example");

    assertThatThrownBy(() -> groupService.removeMember(group.getId(), alice.getId(), admin))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Identitätsanbieter");
    assertThatThrownBy(() -> groupService.deleteGroup(group.getId(), admin))
        .isInstanceOf(ValidationException.class);
    assertThat(groupRepository.findById(group.getId())).isPresent();
  }

  private int historyRowsOfAlice() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM group_membership_history WHERE user_id = ?",
            Integer.class,
            alice.getId());
    return count == null ? 0 : count;
  }
}
