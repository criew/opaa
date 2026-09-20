package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
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
  @Autowired private OidcProviderRepository providerRepository;
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
    jdbcTemplate.update("DELETE FROM oidc_providers WHERE issuer_uri = ?", ISSUER);
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
    OidcProvider provider = persistedProviderWithGroupsClaim();

    eventPublisher.publishEvent(
        UserProvisionedEvent.withTokenGroups(
            user, false, provider, TokenGroups.named(List.of(groupName))));

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
   * provider row that does not exist, so the synchronizer's insert hits {@code fk_groups_provider}
   * - the key that carries "no group without its provider" since #1812.
   */
  @Test
  void aFailingGroupSynchronizationStillLeavesThePersonalSpaceBehind() {
    User user = persistedUser();
    OidcProvider provider = providerWithGroupsClaim();

    assertThatThrownBy(
            () ->
                eventPublisher.publishEvent(
                    UserProvisionedEvent.withTokenGroups(
                        user, false, provider, TokenGroups.named(List.of(groupName)))))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(defaultSpacesOf(user.getId())).isEqualTo(1);
  }

  private User persistedUser() {
    User user = new User(subject, ISSUER, "person@behoerde.example", "Person");
    user.setOrganizationId(Organization.DEFAULT_ID);
    return userRepository.save(user);
  }

  private OidcProvider persistedProviderWithGroupsClaim() {
    return providerRepository.save(providerWithGroupsClaim());
  }

  /** Unpersisted on purpose - {@code groups.provider_id} refuses a group of an unknown provider. */
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
