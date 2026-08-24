package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reproduces and guards the #271 organization-boundary gap in {@code AdminController} / {@link
 * UserService}, against a real Postgres database with the real, versioned Liquibase schema applied
 * ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}) - the same reasoning as {@code
 * SpaceServiceIntegrationTest} (#288): {@code users.organization_id} is a plain {@code UUID} column
 * without {@code @ManyToOne}, so only Liquibase (not Hibernate-generated DDL) creates {@code
 * fk_users_organization}.
 *
 * <p>Before the fix, {@code AdminController#listUsers} called {@code UserService#findAll()} (=
 * {@code userRepository.findAll()}), returning every organization's users to a SYSTEM_ADMIN whose
 * reach must stop at their own organization's boundary (#199) just like every other role, and
 * {@code AdminController#changeRole} called {@code UserService#updateRole} with a bare {@code
 * userId} lookup that never checked the target user's organization at all - reachable from another
 * organization's SYSTEM_ADMIN.
 */
@OpaaIntegrationTest
class UserServiceOrganizationBoundaryIntegrationTest {

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationA;
  private UUID organizationB;

  @BeforeEach
  void setUp() {
    // Deliberately does not delete all organizations: Organization.DEFAULT_ID is seeded once by
    // Liquibase and other tests sharing this Spring context rely on that row existing
    // (fk_users_organization) - see SpaceServiceIntegrationTest's identical reasoning.
    spaceRepository.deleteAll();
    userRepository.deleteAll();
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
    organizationB =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org B")).getId();
  }

  @AfterEach
  void tearDown() {
    spaceRepository.deleteAll();
    userRepository.deleteAll();
    // updateRole writes audit_log rows (fk_audit_log_organization is ON DELETE RESTRICT, migration
    // 017) - purged via JdbcTemplate, same reasoning as SpaceServiceIntegrationTest#tearDown.
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE organization_id IN (?, ?)", organizationA, organizationB);
    organizationRepository.deleteAllById(List.of(organizationA, organizationB));
  }

  private User createUser(UUID organizationId) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user);
  }

  @Test
  void findAllInOrganizationReturnsOnlyTheCallersOwnOrganization() {
    User userInA = createUser(organizationA);
    createUser(organizationB);

    List<User> result = userService.findAllInOrganization(organizationA);

    assertThat(result).extracting(User::getId).containsExactly(userInA.getId());
  }

  @Test
  void updateRoleRejectsATargetUserFromAnotherOrganization() {
    User actor = createUser(organizationA);
    actor.setSystemRole(SystemRole.SYSTEM_ADMIN);
    userRepository.save(actor);
    User targetInOtherOrganization = createUser(organizationB);

    assertThatThrownBy(
            () ->
                userService.updateRole(
                    targetInOtherOrganization.getId(),
                    SystemRole.SYSTEM_ADMIN,
                    CurrentUser.from(actor)))
        .isInstanceOf(UserNotFoundException.class);

    User reloaded = userRepository.findById(targetInOtherOrganization.getId()).orElseThrow();
    assertThat(reloaded.getSystemRole()).isEqualTo(SystemRole.USER);
  }

  @Test
  void updateRoleSucceedsForATargetUserInTheSameOrganization() {
    User actor = createUser(organizationA);
    actor.setSystemRole(SystemRole.SYSTEM_ADMIN);
    userRepository.save(actor);
    User targetInSameOrganization = createUser(organizationA);

    User updated =
        userService.updateRole(
            targetInSameOrganization.getId(), SystemRole.SYSTEM_ADMIN, CurrentUser.from(actor));

    assertThat(updated.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }
}
