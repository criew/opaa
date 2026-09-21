package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.LockReason;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalCredentialsRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The counting query behind {@code GroupMembershipResolver#activeMemberCount} (#1820) against the
 * real schema: it must answer exactly what {@code AccountActivityService} answers for the same set
 * of accounts. The definition of "active" exists twice - once in Java, once as SQL in {@code
 * ActiveAccountSql} - and this parity is what holds the two together.
 */
@OpaaIntegrationTest
class ActiveMemberCountIntegrationTest {

  @Autowired private GroupMembershipResolver resolver;
  @Autowired private GroupRepository groupRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private LocalCredentialsRepository localCredentials;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organization;

  @BeforeEach
  void createOrganization() {
    organization = organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
  }

  @AfterEach
  void tearDown() {
    for (String table : List.of("group_membership_history", "group_memberships", "groups")) {
      jdbcTemplate.update("DELETE FROM " + table + " WHERE organization_id = ?", organization);
    }
    jdbcTemplate.update(
        "DELETE FROM local_credentials WHERE user_id IN"
            + " (SELECT id FROM users WHERE organization_id = ?)",
        organization);
    ownOrganizationFixtures.removeOrganizations(organization);
  }

  /**
   * Every state the definition distinguishes, in one group: a provider account, one locked by the
   * directory synchronisation, and the five local ones - active, invited, expired, locked, and a
   * failed-login lockout that has run out and therefore counts again.
   */
  @Test
  void theCountMatchesTheAccountsTheActivityServiceCallsActive() {
    Instant now = Instant.now();
    UUID providerAccount = createProviderUser();
    UUID lockedByDirectory = createProviderUser();
    lockFromDirectory(lockedByDirectory);
    UUID localActive = createLocalUser(credentials -> activate(credentials, now));
    UUID localInvited = createLocalUser(credentials -> {});
    UUID localExpired =
        createLocalUser(
            credentials -> {
              activate(credentials, now);
              credentials.setExpiresAt(now.minus(1, ChronoUnit.DAYS), now);
            });
    UUID localLocked =
        createLocalUser(
            credentials -> {
              activate(credentials, now);
              credentials.lock(LockReason.ADMIN, now, null);
            });
    UUID lockoutElapsed =
        createLocalUser(
            credentials -> {
              activate(credentials, now);
              credentials.recordLockoutUntil(now.minus(1, ChronoUnit.HOURS), now);
            });

    UUID group =
        createGroup(
            providerAccount,
            lockedByDirectory,
            localActive,
            localInvited,
            localExpired,
            localLocked,
            lockoutElapsed);

    assertThat(resolver.activeMemberIds(group, organization))
        .containsExactlyInAnyOrder(providerAccount, localActive, lockoutElapsed);
    assertThat(resolver.activeMemberCount(group, organization))
        .as("the counting query and the loading one answer the same number")
        .isEqualTo(resolver.activeMemberIds(group, organization).size())
        .isEqualTo(3);
  }

  @Test
  void anEmptyGroupCountsZeroAndAGroupOfAnotherOrganizationCountsNobody() {
    UUID member = createProviderUser();
    UUID group = createGroup(member);

    assertThat(resolver.activeMemberCount(group, organization)).isEqualTo(1);
    assertThat(resolver.activeMemberCount(group, UUID.randomUUID()))
        .as("the organization boundary holds in the counting query too")
        .isZero();
    assertThat(resolver.activeMemberCount(createGroup(), organization)).isZero();
  }

  /**
   * The figure sits on the list path of every space and every subject selection, which is why it is
   * counted in the database rather than sized from the loaded membership (#1820). A group of this
   * size is what made that difference visible.
   */
  @Test
  void aLargeGroupIsCountedWithoutLoadingItsAccounts() {
    UUID[] members = new UUID[2000];
    for (int index = 0; index < members.length; index++) {
      members[index] = createProviderUser();
    }
    UUID group = createGroup(members);

    long countingNanos = System.nanoTime();
    int counted = resolver.activeMemberCount(group, organization);
    countingNanos = System.nanoTime() - countingNanos;

    long loadingNanos = System.nanoTime();
    int loaded = resolver.activeMemberIds(group, organization).size();
    loadingNanos = System.nanoTime() - loadingNanos;

    assertThat(counted).isEqualTo(loaded).isEqualTo(members.length);
    System.out.printf(
        "active member count of %d members: counting query %d ms, loading path %d ms%n",
        members.length, countingNanos / 1_000_000, loadingNanos / 1_000_000);
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private UUID createGroup(UUID... memberIds) {
    Group group = Group.internal(organization, "Referat 50", null, null);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organization));
    }
    UUID groupId = groupRepository.save(group).getId();
    groupMembershipsInvalidated(memberIds);
    return groupId;
  }

  private void groupMembershipsInvalidated(UUID... memberIds) {
    resolver.invalidateUsers(List.of(memberIds));
  }

  private UUID createProviderUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organization);
    return userRepository.save(user).getId();
  }

  private UUID createLocalUser(java.util.function.Consumer<LocalCredentials> state) {
    User user =
        new User(
            UUID.randomUUID().toString(),
            LocalIssuer.URN,
            UUID.randomUUID() + "@example.com",
            "Lokal");
    user.setOrganizationId(organization);
    UUID userId = userRepository.save(user).getId();
    LocalCredentials credentials = new LocalCredentials(userId, "Test", Instant.now());
    state.accept(credentials);
    localCredentials.save(credentials);
    return userId;
  }

  /** Password set and address confirmed - what {@code LocalAccountState.ACTIVE} asks for. */
  private static void activate(LocalCredentials credentials, Instant now) {
    credentials.setPasswordHash("hash", now);
    credentials.markEmailVerified(now);
  }

  private void lockFromDirectory(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    user.lockFromDirectory(Instant.now());
    userRepository.save(user);
  }
}
