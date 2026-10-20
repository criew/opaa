package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.account.AccountActivityService;
import io.opaa.api.types.SystemRole;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.instance.PostgresInvalidationBus;
import io.opaa.permission.GroupMembershipChangeListener;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupMembershipSource;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnUserFixtures;
import io.opaa.test.ProviderFixtures;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two instances against one database (ADR-0053, Entscheidung 5): this context is instance A, and
 * instance B is a second invalidation bus with its own listening connection and its own provider
 * registry and membership cache over the same database. What A changes - a provider's role mapping,
 * the provider itself, the switch of the local accounts, a group's deactivation - takes effect on B
 * without a restart, once B has heard every announcement committed before.
 */
@OpaaIntegrationTest
class ChangesOnAllInstancesIntegrationTest {

  private static final Duration DELIVERY = Duration.ofSeconds(10);
  private static final String OLD_ADMIN_ROLE = "opaa-admins";
  private static final String NEW_ADMIN_ROLE = "opaa-systemverwaltung";
  private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private OidcProviderRepository providers;
  @Autowired private UserRepository users;
  @Autowired private TokenRoleSynchronizer roleSynchronizer;
  @Autowired private GroupMembershipSource membershipSource;
  @Autowired private AccountActivityService accountActivity;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcConnectionDetails connectionDetails;
  @Autowired private TransactionTemplate transactions;
  @Autowired private OwnUserFixtures ownUsers;
  @Autowired private Clock clock;

  private final List<UUID> ownProviders = new ArrayList<>();
  private final List<UUID> ownGroups = new ArrayList<>();
  private Set<UUID> foreignUsers;
  private Boolean localSwitchBefore;
  private UUID createdLocalRow;

  private PostgresInvalidationBus busOfB;
  private OidcProviderRegistry registryOfB;
  private GroupMembershipResolver membershipOfB;

  @BeforeEach
  void startInstanceB() {
    foreignUsers = ownUsers.existingUserIds();
    StaticListableBeanFactory beansOfB = new StaticListableBeanFactory();
    busOfB =
        new PostgresInvalidationBus(
            jdbc,
            dataSource,
            PostgresInvalidationBus.opener(
                connectionDetails.getJdbcUrl(),
                connectionDetails.getUsername(),
                connectionDetails.getPassword(),
                "opaa-test-instance-b"),
            beansOfB.getBeanProvider(InvalidationSubscriber.class),
            Duration.ofMillis(200));
    registryOfB =
        new OidcProviderRegistry(
            providers,
            provider ->
                token -> {
                  throw new BadJwtException("instance B verifies no token in this test");
                },
            new OidcAddressPolicy(TargetAddressValidator.disabled()),
            clock,
            busOfB);
    membershipOfB =
        new GroupMembershipResolver(
            membershipSource,
            users,
            accountActivity,
            new StaticListableBeanFactory().getBeanProvider(GroupMembershipChangeListener.class),
            busOfB);
    beansOfB.addBean("registry", registryOfB);
    beansOfB.addBean("membership", membershipOfB);
    busOfB.start();
    assertThat(busOfB.isListening()).isTrue();
  }

  @AfterEach
  void stopInstanceBAndCleanUp() {
    busOfB.stop();
    ownGroups.forEach(
        group -> {
          jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", group);
          jdbc.update("DELETE FROM groups WHERE id = ?", group);
        });
    ownGroups.clear();
    ownUsers.removeUsersCreatedSince(foreignUsers);
    ownProviders.forEach(providers::deleteById);
    ownProviders.clear();
    if (createdLocalRow != null) {
      providers.deleteById(createdLocalRow);
      createdLocalRow = null;
    } else if (localSwitchBefore != null) {
      setLocalSwitch(localSwitchBefore);
    }
    localSwitchBefore = null;
  }

  @Test
  void aRoleMappingChangedOnAIsTheOneBWritesTheSystemRoleBy() {
    OidcProvider provider = providerWithAdminRole(OLD_ADMIN_ROLE);
    User user = users.save(user(provider.getIssuerUri()));
    assertThat(registryOfB.findEnabledByIssuer(provider.getIssuerUri())).isPresent();

    transactions.executeWithoutResult(
        status -> {
          OidcProvider row = providers.findById(provider.getId()).orElseThrow();
          row.replaceDetails(
              row.getDisplayName(),
              row.getIssuerUri(),
              row.getClientId(),
              row.getJwkSetUri(),
              mapping(NEW_ADMIN_ROLE));
          providers.save(row);
        });
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();

    OidcProvider seenByB = registryOfB.findEnabledByIssuer(provider.getIssuerUri()).orElseThrow();
    User synced =
        roleSynchronizer.apply(user, seenByB, new TokenRoles.Named(List.of(OLD_ADMIN_ROLE)));

    assertThat(synced.getSystemRole()).isEqualTo(SystemRole.USER);
    assertThat(users.findById(user.getId()).orElseThrow().getSystemRole())
        .as("B wrote no system role by the old mapping")
        .isEqualTo(SystemRole.USER);
  }

  @Test
  void aProviderDisabledOnAIsRefusedOnB() {
    OidcProvider provider = providerWithAdminRole(OLD_ADMIN_ROLE);
    assertThat(registryOfB.findEnabledByIssuer(provider.getIssuerUri())).isPresent();

    transactions.executeWithoutResult(
        status -> {
          OidcProvider row = providers.findById(provider.getId()).orElseThrow();
          row.disable();
          providers.save(row);
        });
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();

    assertThat(registryOfB.findEnabledByIssuer(provider.getIssuerUri())).isEmpty();
    assertThatThrownBy(
            () ->
                registryOfB
                    .resolve(provider.getIssuerUri())
                    .authenticate(new BearerTokenAuthenticationToken("token")))
        .hasMessageContaining(OidcProviderRegistry.UNKNOWN_ISSUER);
  }

  @Test
  void localAccountsSwitchedOffOnAAreSwitchedOffOnB() {
    setLocalSwitch(true);
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
    assertThat(registryOfB.localAccountsEnabled()).isTrue();

    setLocalSwitch(false);
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();

    assertThat(registryOfB.localAccountsEnabled()).isFalse();
  }

  @Test
  void aGroupDeactivatedOnAStopsTakingEffectOnB() {
    User member = users.save(user("https://issuer.example"));
    UUID group = UUID.randomUUID();
    ownGroups.add(group);
    jdbc.update(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', 'Gruppe')",
        group,
        member.getOrganizationId());
    jdbc.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id) VALUES (?, ?, ?,"
            + " ?)",
        UUID.randomUUID(),
        member.getId(),
        group,
        member.getOrganizationId());
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
    assertThat(membershipOfB.groupIdsForUser(member.getId())).contains(group);

    jdbc.update("UPDATE groups SET deactivated = true, deactivated_at = now() WHERE id = ?", group);
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();

    assertThat(membershipOfB.groupIdsForUser(member.getId())).doesNotContain(group);
  }

  private OidcProvider providerWithAdminRole(String adminRole) {
    OidcProvider provider = ProviderFixtures.tokenProvider(providers);
    ownProviders.add(provider.getId());
    provider.replaceDetails(
        provider.getDisplayName(),
        provider.getIssuerUri(),
        provider.getClientId(),
        provider.getJwkSetUri(),
        mapping(adminRole));
    OidcProvider saved = providers.save(provider);
    assertThat(busOfB.awaitDelivery(DELIVERY)).isTrue();
    return saved;
  }

  private static User user(String issuer) {
    User user = new User(UUID.randomUUID().toString(), issuer, null, "Erika Muster");
    user.setOrganizationId(ORGANIZATION);
    return user;
  }

  private static OidcClaimMapping mapping(String adminRole) {
    return new OidcClaimMapping(null, null, "roles", adminRole, null, null);
  }

  /** Creates the LOCAL row on first use and remembers what to restore. */
  private void setLocalSwitch(boolean enabled) {
    transactions.executeWithoutResult(
        status -> {
          OidcProvider local =
              providers.findAll().stream().filter(OidcProvider::isLocal).findFirst().orElse(null);
          if (local == null) {
            local = providers.save(OidcProvider.localProvider("Lokale Konten"));
            createdLocalRow = local.getId();
          } else if (localSwitchBefore == null && createdLocalRow == null) {
            localSwitchBefore = local.isEnabled();
          }
          if (enabled) {
            local.enable();
          } else {
            local.disable();
          }
          providers.save(local);
        });
  }
}
