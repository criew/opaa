package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupMechanism;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.auth.oidc.OidcProviderService;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupProviderView;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The run is bound to one identity provider row (#1816, ADR-0036 Entscheidung 2): only that
 * provider's groups change, only its accounts are resolved, a disabled provider does not run, and
 * the two group mechanisms of one provider can never be on at once.
 *
 * <p>Also holds the {@code dev}-mode decision of #1816 (ADR-0036, Entscheidung 11): there is no
 * synthetic provider row, so an installation without one - the {@code dev} mode, which this context
 * runs in - has nothing to run, and a run addressed at a provider that does not exist answers with
 * "not found" rather than inventing a binding.
 */
@OpaaIntegrationTest
class DirectorySyncProviderBindingIntegrationTest {

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private OidcProviderService providerService;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncPendingPlanRepository pendingPlanRepository;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private io.opaa.permission.GroupSubjectDirectory groupSubjectDirectory;
  @Autowired private io.opaa.group.GroupService groupService;

  private static final UUID ORGANIZATION_ID = Organization.DEFAULT_ID;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdProviderIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    cleanUp();
    directoryClient.respondWith();
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  private void cleanUp() {
    createdProviderIds.forEach(
        providerId -> {
          pendingPlanRepository
              .findByOrganizationIdAndProviderId(ORGANIZATION_ID, providerId)
              .ifPresent(pendingPlanRepository::delete);
          statusRepository
              .findByOrganizationIdAndProviderId(ORGANIZATION_ID, providerId)
              .ifPresent(statusRepository::delete);
        });
    List<Group> ownGroups =
        groupRepository.findByOrganizationId(ORGANIZATION_ID).stream()
            .filter(
                group ->
                    createdGroupIds.contains(group.getId())
                        || createdProviderIds.contains(group.getProviderId()))
            .toList();
    membershipRepository.deleteAll(
        ownGroups.stream()
            .flatMap(group -> membershipRepository.findByGroupId(group.getId()).stream())
            .toList());
    groupRepository.deleteAll(ownGroups);
    membershipHistoryRepository.deleteByUserIdIn(List.copyOf(createdUserIds));
    userRepository.deleteAllById(createdUserIds);
    createdProviderIds.forEach(providerRepository::deleteById);
    createdUserIds.clear();
    createdProviderIds.clear();
    createdGroupIds.clear();
  }

  // ---------------------------------------------------------------------------------------
  // Per provider
  // ---------------------------------------------------------------------------------------

  /** Acceptance criterion of #1816: two providers, one with the run switched on. */
  @Test
  void onlyTheProviderWithTheRunSwitchedOnHasItsGroupsAndMembershipsChanged() {
    OidcProvider synchronised = createProvider(true);
    OidcProvider untouched = createProvider(false);
    UUID memberOfSynchronised = createUser("member-1", synchronised.getIssuerUri());
    UUID memberOfUntouched = createUser("member-1", untouched.getIssuerUri());
    Group ownGroup = persistOrgUnit(synchronised.getId(), "dir-1", "Referat 50");
    Group foreignGroup =
        persistOrgUnit(untouched.getId(), "dir-2", "Referat 60", memberOfUntouched);

    directoryClient.respondWithFor(
        synchronised.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of("member-1")));

    SyncReport report = directorySyncService.run(ORGANIZATION_ID, synchronised.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(membershipRepository.findByGroupId(ownGroup.getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactly(memberOfSynchronised);
    // The other provider's group is invisible to this run: neither dissolved nor emptied.
    Group reloadedForeign = groupRepository.findById(foreignGroup.getId()).orElseThrow();
    assertThat(reloadedForeign.isDissolved()).isFalse();
    assertThat(membershipRepository.findByGroupId(foreignGroup.getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactly(memberOfUntouched);
  }

  /** The run of a provider whose row is switched off pauses (ADR-0036, Entscheidung 2). */
  @Test
  void theRunOfADisabledProviderIsRefusedRatherThanSilentlySkipped() {
    OidcProvider provider = createProvider(true);
    provider.disable();
    providerRepository.save(provider);

    assertThatThrownBy(() -> directorySyncService.run(ORGANIZATION_ID, provider.getId()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("deaktiviert");
    assertThat(
            ((ConflictException)
                    org.assertj.core.api.Assertions.catchThrowable(
                        () -> directorySyncService.run(ORGANIZATION_ID, provider.getId())))
                .getCode())
        .isEqualTo(DirectorySyncService.PROVIDER_DISABLED_CODE);
  }

  @Test
  void aProviderWithoutTheRunSwitchedOnIsRefusedWithItsOwnCode() {
    OidcProvider provider = createProvider(false);

    assertThat(
            ((ConflictException)
                    org.assertj.core.api.Assertions.catchThrowable(
                        () -> directorySyncService.dryRun(ORGANIZATION_ID, provider.getId())))
                .getCode())
        .isEqualTo(DirectorySyncService.NOT_ENABLED_CODE);
  }

  /**
   * The {@code dev}-mode decision of #1816: no synthetic provider row, so without one there is
   * nothing to run at all - and this context runs in the {@code dev} mode.
   */
  @Test
  void withoutAProviderRowThereIsNothingToRun() {
    UUID absentProvider = UUID.randomUUID();

    assertThatThrownBy(() -> directorySyncService.run(ORGANIZATION_ID, absentProvider))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> directorySyncService.dryRun(ORGANIZATION_ID, absentProvider))
        .isInstanceOf(NotFoundException.class);
    // No status line is invented for it either - the overview names providers, not bindings.
    assertThat(directorySyncService.listStatus(ORGANIZATION_ID))
        .extracting(DirectorySyncStatusView::providerId)
        .doesNotContain(absentProvider);
  }

  // ---------------------------------------------------------------------------------------
  // One mechanism per provider
  // ---------------------------------------------------------------------------------------

  @Test
  void theRunCannotBeSwitchedOnWhileTheProviderCarriesAGroupsClaim() {
    OidcProvider provider = createProviderWithGroupsClaim();

    assertThat(
            ((ConflictException)
                    org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                            providerService.setDirectorySync(
                                ORGANIZATION_ID, null, provider.getId(), true, 360)))
                .getCode())
        .isEqualTo(OidcProviderService.DIRECTORY_SYNC_MECHANISM_CONFLICT);
    assertThat(providerRepository.findById(provider.getId()).orElseThrow().isDirectorySyncEnabled())
        .isFalse();
  }

  @Test
  void aGroupsClaimCannotBeSetWhileTheRunIsSwitchedOn() {
    OidcProvider provider = createProvider(true);

    assertThat(
            ((ConflictException)
                    org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                            providerService.updateProvider(
                                ORGANIZATION_ID,
                                null,
                                provider.getId(),
                                new io.opaa.auth.oidc.OidcProviderDraft(
                                    provider.getDisplayName(),
                                    provider.getIssuerUri(),
                                    "opaa-frontend",
                                    null,
                                    new OidcClaimMapping(
                                        "email", "name", null, null, null, "groups")))))
                .getCode())
        .isEqualTo(OidcProviderService.DIRECTORY_SYNC_MECHANISM_CONFLICT);
  }

  /**
   * ADR-0036, Entscheidung 3 and Personalrat C4: switching the mechanism names the token groups as
   * no longer maintained and revokes nothing.
   */
  @Test
  void theSwitchOfMechanismFreezesTheTokenGroupsAndNamesThemInTheReport() {
    OidcProvider provider = createProvider(true);
    UUID member = createUser("member-1", provider.getIssuerUri());
    Group tokenGroup =
        groupRepository.save(
            new Group(
                ORGANIZATION_ID,
                GroupKind.IDENTITY_PROVIDER,
                "Referat 12",
                null,
                provider.getId(),
                "Referat 12",
                null,
                null));
    tokenGroup.addMembership(new GroupMembership(member, ORGANIZATION_ID));
    groupRepository.save(tokenGroup);
    createdGroupIds.add(tokenGroup.getId());

    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of("member-1")));

    SyncReport report = directorySyncService.run(ORGANIZATION_ID, provider.getId());

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.unmaintainedTokenGroups())
        .extracting(GroupChange::name)
        .containsExactly("Referat 12");
    // Nothing revoked: read back from the database, not from the report.
    Group reloaded = groupRepository.findById(tokenGroup.getId()).orElseThrow();
    assertThat(reloaded.getKind()).isEqualTo(GroupKind.IDENTITY_PROVIDER);
    assertThat(reloaded.isDissolved()).isFalse();
    assertThat(membershipRepository.findByGroupId(tokenGroup.getId()))
        .extracting(GroupMembership::getUserId)
        .containsExactly(member);
  }

  /**
   * The other half of "nothing is revoked silently" (#1816, ADR-0036 Entscheidung 3): a frozen
   * token group keeps what it holds, but a group nobody can ever join or leave again must not
   * become a new grant target.
   */
  @Test
  void aTokenGroupOfAProviderWithTheRunIsMarkedAsNoLongerMaintained() {
    OidcProvider provider = createProvider(true);
    Group tokenGroup =
        groupRepository.save(
            new Group(
                ORGANIZATION_ID,
                GroupKind.IDENTITY_PROVIDER,
                "Referat 12",
                null,
                provider.getId(),
                "Referat 12",
                null,
                null));
    createdGroupIds.add(tokenGroup.getId());

    // The refusal itself is AssetGrantServiceTest's; what this asserts is the derivation - that
    // the adapter marks exactly this group, from the provider row and the group's kind.
    assertThat(groupSubjectDirectory.find(tokenGroup.getId()).orElseThrow().unmaintained())
        .isTrue();
  }

  /** The same group at a provider still fed by its claim stays an ordinary grant target. */
  @Test
  void aTokenGroupOfAProviderWithoutTheRunIsNotMarked() {
    OidcProvider provider = createProviderWithGroupsClaim();
    Group tokenGroup =
        groupRepository.save(
            new Group(
                ORGANIZATION_ID,
                GroupKind.IDENTITY_PROVIDER,
                "Referat 13",
                null,
                provider.getId(),
                "Referat 13",
                null,
                null));
    createdGroupIds.add(tokenGroup.getId());

    assertThat(groupSubjectDirectory.find(tokenGroup.getId()).orElseThrow().unmaintained())
        .isFalse();
  }

  // ---------------------------------------------------------------------------------------
  // What a member sees about the delay
  // ---------------------------------------------------------------------------------------

  /**
   * "Meine Gruppen" names mechanism, interval and last run per provider (ADR-0036, Entscheidung 3).
   * Asserted at the producer, not only at the mapper: the mapper copies whatever it is handed, and
   * handing it the wrong mechanism would look identical there.
   */
  @Test
  void myGroupsNamesTheMechanismTheIntervalAndTheLastRunOfTheProvider() {
    OidcProvider provider = createProvider(true);
    UUID member = createUser("member-1", provider.getIssuerUri());
    Group unit = persistOrgUnit(provider.getId(), "dir-1", "Referat 50", member);
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, Set.of("member-1")));
    directorySyncService.run(ORGANIZATION_ID, provider.getId());
    Instant lastRunAt =
        statusRepository
            .findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId())
            .orElseThrow()
            .getLastRunAt();

    GroupProviderView view =
        groupService.listMyGroups(currentUser(member)).stream()
            .filter(overview -> overview.group().getId().equals(unit.getId()))
            .map(GroupOverview::provider)
            .findFirst()
            .orElseThrow();

    assertThat(view.mechanism()).isEqualTo(GroupMechanism.DIRECTORY);
    assertThat(view.syncIntervalMinutes()).isEqualTo(360);
    assertThat(view.lastSyncAt()).isEqualTo(lastRunAt);
    assertThat(view.enabled()).isTrue();
  }

  /** A provider fed by its claim names no interval and no last run - there is none. */
  @Test
  void aTokenProvidersGroupsNameNoIntervalAndNoLastRun() {
    OidcProvider provider = createProviderWithGroupsClaim();
    UUID member = createUser("member-2", provider.getIssuerUri());
    Group tokenGroup =
        new Group(
            ORGANIZATION_ID,
            GroupKind.IDENTITY_PROVIDER,
            "Referat 12",
            null,
            provider.getId(),
            "Referat 12",
            null,
            null);
    tokenGroup.addMembership(new GroupMembership(member, ORGANIZATION_ID));
    createdGroupIds.add(groupRepository.save(tokenGroup).getId());

    GroupProviderView view =
        groupService.listMyGroups(currentUser(member)).stream()
            .filter(overview -> overview.group().getId().equals(tokenGroup.getId()))
            .map(GroupOverview::provider)
            .findFirst()
            .orElseThrow();

    assertThat(view.mechanism()).isEqualTo(GroupMechanism.TOKEN);
    assertThat(view.syncIntervalMinutes()).isNull();
    assertThat(view.lastSyncAt()).isNull();
  }

  // ---------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------

  private OidcProvider createProvider(boolean directorySyncEnabled) {
    OidcProvider provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    if (directorySyncEnabled) {
      provider.configureDirectorySync(true, 360);
    }
    providerRepository.save(provider);
    createdProviderIds.add(provider.getId());
    return provider;
  }

  private OidcProvider createProviderWithGroupsClaim() {
    OidcProvider provider =
        new OidcProvider(
            "Token-Anbieter " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            new OidcClaimMapping("email", "name", null, null, null, "groups"));
    providerRepository.save(provider);
    createdProviderIds.add(provider.getId());
    return provider;
  }

  private UUID createUser(String subject, String issuer) {
    User user = new User(subject, issuer, subject + "@example.com", "Test User");
    user.setOrganizationId(ORGANIZATION_ID);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  /** The caller "Meine Gruppen" is asked for - only the id and the organization matter here. */
  private io.opaa.auth.CurrentUser currentUser(UUID userId) {
    return io.opaa.auth.CurrentUser.of(
        userId, ORGANIZATION_ID, io.opaa.api.types.SystemRole.USER, "Test User");
  }

  private Group persistOrgUnit(UUID providerId, String externalId, String name, UUID... memberIds) {
    Group group =
        new Group(
            ORGANIZATION_ID, GroupKind.ORG_UNIT, name, null, providerId, externalId, null, null);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, ORGANIZATION_ID));
    }
    Group saved = groupRepository.save(group);
    createdGroupIds.add(saved.getId());
    return saved;
  }
}
