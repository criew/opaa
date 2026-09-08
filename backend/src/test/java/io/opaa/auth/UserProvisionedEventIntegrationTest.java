package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.group.TokenGroupSynchronizer;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The wiring of {@link UserProvisionedEvent} against a real Postgres and the real listener beans: a
 * sign-in provisions the personal space, and an event carrying a provider's groups claim creates
 * the identity-provider group and its membership - neither reached from {@code auth} by a direct
 * call anymore. Nothing is mocked here; a broken listener registration would show up as a missing
 * row rather than as a passing verification on a stub.
 */
@OpaaIntegrationTest
class UserProvisionedEventIntegrationTest {

  private static final String ISSUER = "https://idp.example/realms/a";

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final String subject = "sub-" + UUID.randomUUID();
  private final String groupName = "Fachbereich " + UUID.randomUUID();

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE object_id IN (SELECT id::text FROM groups WHERE name = ?)",
        groupName);
    jdbcTemplate.update(
        "DELETE FROM group_membership_history WHERE group_id IN (SELECT id FROM groups WHERE name ="
            + " ?)",
        groupName);
    jdbcTemplate.update(
        "DELETE FROM group_memberships WHERE group_id IN (SELECT id FROM groups WHERE name = ?)",
        groupName);
    jdbcTemplate.update("DELETE FROM groups WHERE name = ?", groupName);
    jdbcTemplate.update(
        "DELETE FROM spaces WHERE owner_id IN (SELECT id FROM users WHERE subject = ?)", subject);
    jdbcTemplate.update("DELETE FROM users WHERE subject = ?", subject);
  }

  @Test
  void aSignInProvisionsThePersonalSpaceThroughTheEvent() {
    User user = userService.findOrCreateUser(subject, ISSUER, "person@behoerde.example", "Person");

    assertThat(defaultSpacesOf(user.getId())).isEqualTo(1);
  }

  @Test
  void anEventWithTokenGroupsCreatesTheGroupAndTheMembership() {
    User user = persistedUser();
    OidcProvider provider = providerWithGroupsClaim();

    eventPublisher.publishEvent(
        UserProvisionedEvent.withTokenGroups(user, false, provider, List.of(groupName)));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM group_memberships m JOIN groups g ON g.id = m.group_id WHERE"
                    + " g.name = ? AND m.user_id = ?",
                Integer.class,
                groupName,
                user.getId()))
        .isEqualTo(1);
    // the same event provisions the personal space, so both listeners see every sign-in
    assertThat(defaultSpacesOf(user.getId())).isEqualTo(1);
  }

  /**
   * The listener order is a contract, not a preference: the personal space must already be in place
   * when a rights-affecting listener of the same event fails. The failure is provoked with a
   * conflicting {@code external_id} the synchronizer's own kind filter cannot see, so its insert
   * hits {@code uk_groups_organization_external_id}.
   */
  @Test
  void aFailingGroupSynchronizationStillLeavesThePersonalSpaceBehind() {
    User user = persistedUser();
    OidcProvider provider = providerWithGroupsClaim();
    jdbcTemplate.update(
        "INSERT INTO groups (id, organization_id, kind, name, external_id) VALUES (?, ?, 'ORG_UNIT',"
            + " ?, ?)",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        groupName,
        TokenGroupSynchronizer.namespaceOf(provider) + groupName);

    assertThatThrownBy(
            () ->
                eventPublisher.publishEvent(
                    UserProvisionedEvent.withTokenGroups(
                        user, false, provider, List.of(groupName))))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(defaultSpacesOf(user.getId())).isEqualTo(1);
  }

  private User persistedUser() {
    User user = new User(subject, ISSUER, "person@behoerde.example", "Person");
    user.setOrganizationId(Organization.DEFAULT_ID);
    return userRepository.save(user);
  }

  private static OidcProvider providerWithGroupsClaim() {
    return new OidcProvider(
        "Beschäftigte",
        ISSUER,
        "opaa-frontend",
        null,
        new OidcClaimMapping(null, null, null, null, null, "groups"));
  }

  private int defaultSpacesOf(UUID userId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM spaces WHERE owner_id = ? AND is_default = true",
        Integer.class,
        userId);
  }
}
