package io.opaa.asset;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.library.KnowledgeLibraryCatalogFacts;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetType;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptLibraryRepository;
import io.opaa.prompt.PromptLibraryService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The catalog's filters, orders and tile fields (#2093): visibility, "aus meinen Gruppen", sorting
 * by name and by last change, the caller's role, the owner, the status and the facts of a knowledge
 * library - each filter narrowing only the readable set, so an asset the caller may not read is
 * absent under every combination.
 */
@OpaaIntegrationTest
class AssetCatalogFilterIntegrationTest {

  private static final String PUBLIC = "Öffentlich";
  private static final String PUBLIC_AND_GROUP = "Öffentlich und Gruppe";
  private static final String GROUP_GRANT = "Gruppenfreigabe";
  private static final String GROUP_OWNED = "Gruppeneigentum";
  private static final String DIRECT = "Direkt";
  private static final String EXPIRED_PUBLIC = "Abgelaufen öffentlich";
  private static final String EXPIRED_GROUP_GRANT = "Abgelaufene Gruppenfreigabe";
  private static final String PUBLIC_KNOWLEDGE = "Wissen öffentlich";

  /** None of these may ever reach the member - not by name, not in a count. */
  private static final List<String> UNREADABLE_FOR_MEMBER =
      List.of("Geschlossen", "Fremde Gruppe", "Gruppeneigentum ohne Recht");

  private static final List<AssetType> TYPES_AND_ALL =
      Arrays.asList(null, PromptLibrary.ASSET_TYPE, KnowledgeLibrary.ASSET_TYPE);

  @Autowired private AssetCatalogService catalogService;
  @Autowired private PromptLibraryRepository promptLibraryRepository;
  @Autowired private KnowledgeLibraryRepository knowledgeLibraryRepository;
  @Autowired private PromptLibraryService promptLibraryService;
  @Autowired private KnowledgeLibraryService knowledgeLibraryService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organization;
  private UUID owner;
  private UUID member;
  private UUID stranger;
  private UUID administrator;
  private UUID myGroup;
  private UUID otherGroup;

  @BeforeEach
  void setUp() {
    organization =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Katalogfilter " + UUID.randomUUID()))
            .getId();
    owner = createUser("Eigentümerin");
    member = createUser("Mitglied");
    stranger = createUser("Fremdes Mitglied");
    administrator = createUser("Systemverwaltung");
    myGroup = createGroup("Referat 50", member);
    otherGroup = createGroup("Referat 12", stranger);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM indexing_jobs WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM assets WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM asset_grant_history WHERE organization_id = ?", organization);
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM succession_cases WHERE organization_id = ?", organization);
    jdbcTemplate.update(
        "DELETE FROM group_membership_history WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM group_memberships WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM groups WHERE organization_id = ?", organization);
    ownOrganizationFixtures.removeOrganizations(organization);
  }

  @Test
  void theVisibilityFilterSplitsTheReadableSetByTheGrantToAllAccounts() {
    createTheMembersCatalog();

    assertThat(names(query(null, AssetCatalogVisibility.PUBLIC, false)))
        .containsExactlyInAnyOrder(PUBLIC, PUBLIC_AND_GROUP, PUBLIC_KNOWLEDGE);
    assertThat(names(query(null, AssetCatalogVisibility.RESTRICTED, false)))
        .as("an expired grant to all accounts makes nothing public")
        .containsExactlyInAnyOrder(
            GROUP_GRANT, GROUP_OWNED, DIRECT, EXPIRED_PUBLIC, EXPIRED_GROUP_GRANT);
    assertThat(names(query(null, null, false)))
        .containsExactlyInAnyOrder(
            PUBLIC,
            PUBLIC_AND_GROUP,
            PUBLIC_KNOWLEDGE,
            GROUP_GRANT,
            GROUP_OWNED,
            DIRECT,
            EXPIRED_PUBLIC,
            EXPIRED_GROUP_GRANT);
  }

  @Test
  void fromMyGroupsKeepsGrantsToAndOwnershipByTheCallersGroups() {
    createTheMembersCatalog();

    assertThat(names(query(null, null, true)))
        .as("a grant to my group, ownership by my group - not a grant to all or to me in person")
        .containsExactlyInAnyOrder(PUBLIC_AND_GROUP, GROUP_GRANT, GROUP_OWNED);
    assertThat(names(query(null, AssetCatalogVisibility.PUBLIC, true)))
        .containsExactly(PUBLIC_AND_GROUP);
    assertThat(names(query(null, AssetCatalogVisibility.RESTRICTED, true)))
        .containsExactlyInAnyOrder(GROUP_GRANT, GROUP_OWNED);
    assertThat(names(query(KnowledgeLibrary.ASSET_TYPE, null, true))).isEmpty();
  }

  @Test
  void aCallerWithoutAnyGroupFindsNothingFromTheirGroups() {
    createTheMembersCatalog();

    AssetCatalogPage page =
        catalogService.list(
            callerOf(owner),
            new AssetCatalogQuery(null, null, null, true, AssetCatalogSort.NAME),
            0,
            200);

    assertThat(page.entries()).isEmpty();
    assertThat(page.totalElements()).isZero();
  }

  @Test
  void anAssetTheCallerMayNotReadIsAbsentUnderEveryCombination() {
    createTheMembersCatalog();

    List<String> combinations = new ArrayList<>();
    for (AssetType type : TYPES_AND_ALL) {
      for (AssetCatalogVisibility visibility : visibilitiesAndBoth()) {
        for (boolean fromMyGroups : List.of(false, true)) {
          for (AssetCatalogSort sort : AssetCatalogSort.values()) {
            for (String text : Arrays.asList(null, "e")) {
              AssetCatalogPage page =
                  catalogService.list(
                      callerOf(member),
                      new AssetCatalogQuery(type, text, visibility, fromMyGroups, sort),
                      0,
                      200);
              String combination =
                  type + "/" + visibility + "/" + fromMyGroups + "/" + sort + "/" + text;
              combinations.add(combination);
              assertThat(names(page))
                  .as(combination)
                  .doesNotContainAnyElementsOf(UNREADABLE_FOR_MEMBER);
              assertThat(page.totalElements())
                  .as("the count includes no unreadable asset: %s", combination)
                  .isEqualTo(page.entries().size());
            }
          }
        }
      }
    }
    assertThat(combinations).hasSize(3 * 3 * 2 * 2 * 2);
  }

  @Test
  void sortByUpdatedAtPutsTheMostRecentFirstAndBreaksTiesById() {
    UUID older = promptLibrary("A älter", owner, true);
    UUID newer = promptLibrary("Z neuer", owner, true);
    UUID tieOne = promptLibrary("M gleich eins", owner, true);
    UUID tieTwo = promptLibrary("M gleich zwei", owner, true);
    Instant base = Instant.parse("2026-09-01T10:00:00Z");
    setUpdatedAt(older, base);
    setUpdatedAt(newer, base.plus(2, ChronoUnit.DAYS));
    setUpdatedAt(tieOne, base.plus(1, ChronoUnit.DAYS));
    setUpdatedAt(tieTwo, base.plus(1, ChronoUnit.DAYS));
    UUID firstTie = tieOne.compareTo(tieTwo) < 0 ? tieOne : tieTwo;
    UUID secondTie = firstTie.equals(tieOne) ? tieTwo : tieOne;

    AssetCatalogPage byUpdate =
        catalogService.list(callerOf(member), sorted(AssetCatalogSort.UPDATED_AT), 0, 200);
    AssetCatalogPage byName =
        catalogService.list(callerOf(member), sorted(AssetCatalogSort.NAME), 0, 200);

    assertThat(byUpdate.entries().stream().map(entry -> entry.asset().getId()).toList())
        .containsExactly(newer, firstTie, secondTie, older);
    assertThat(byUpdate.entries().getFirst().asset().getUpdatedAt())
        .isEqualTo(base.plus(2, ChronoUnit.DAYS));
    assertThat(names(byName))
        .containsExactly("A älter", "M gleich eins", "M gleich zwei", "Z neuer");
    AssetCatalogPage secondPage =
        catalogService.list(callerOf(member), sorted(AssetCatalogSort.UPDATED_AT), 1, 2);
    assertThat(secondPage.entries().stream().map(entry -> entry.asset().getId()).toList())
        .as("paging follows the same order")
        .containsExactly(secondTie, older);
  }

  @Test
  void visibilityAgreesWithTheReachOfTheDetailView() {
    createTheMembersCatalog();
    UUID knowledgeExpired =
        ownerOnlyKnowledgeLibrary("Wissen abgelaufen", owner, SourceType.UPLOAD);
    grantRepository.save(
        AssetGrant.forAllAccounts(
            KnowledgeLibrary.ASSET_TYPE,
            knowledgeExpired,
            organization,
            AssetRole.VIEWER,
            Instant.now().minus(1, ChronoUnit.DAYS),
            owner));
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            knowledgeExpired,
            organization,
            member,
            AssetRole.VIEWER,
            null,
            owner));

    List<AssetCatalogEntry> entries = query(null, null, false).entries();

    assertThat(entries).hasSizeGreaterThanOrEqualTo(9);
    for (AssetCatalogEntry entry : entries) {
      UUID id = entry.asset().getId();
      boolean allAccounts =
          entry.asset().getAssetType().equals(KnowledgeLibrary.ASSET_TYPE)
              ? knowledgeLibraryService.getLibrary(id, callerOf(member)).reach().allAccounts()
              : promptLibraryService.get(id, callerOf(member)).reach().allAccounts();
      assertThat(entry.visibility() == AssetCatalogVisibility.PUBLIC)
          .as("visibility of %s", entry.asset().getName())
          .isEqualTo(allAccounts);
    }
  }

  @Test
  void anEntryCarriesTheCallersRoleByTheFormulaAndItsOwner() {
    UUID mine = promptLibrary("Eigene", member, false);
    UUID edited = promptLibrary("Bearbeitbar", owner, false);
    grantRepository.save(
        AssetGrant.forGroup(
            PromptLibrary.ASSET_TYPE,
            edited,
            organization,
            myGroup,
            AssetRole.EDITOR,
            null,
            owner,
            1));
    UUID groupOwned =
        promptLibraryRepository
            .save(PromptLibrary.ownedByGroup(organization, "Referatsvorlagen", null, myGroup))
            .getId();
    grantRepository.save(
        AssetGrant.forGroup(
            PromptLibrary.ASSET_TYPE,
            groupOwned,
            organization,
            myGroup,
            AssetRole.MANAGER,
            null,
            owner,
            null));
    UUID released = promptLibrary("Für alle", owner, true);

    List<AssetCatalogEntry> forMember = query(null, null, false).entries();
    List<AssetCatalogEntry> forAdministrator =
        catalogService.list(callerOf(administrator, true), null, null, 0, 200).entries();

    assertThat(entryFor(forMember, mine).myRole()).isEqualTo(AssetRole.OWNER);
    assertThat(entryFor(forMember, edited).myRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(entryFor(forMember, groupOwned).myRole()).isEqualTo(AssetRole.MANAGER);
    assertThat(entryFor(forMember, released).myRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(entryFor(forMember, groupOwned).asset().getOwnerId()).isEqualTo(myGroup);
    assertThat(entryFor(forMember, mine).asset().getOwnerId()).isEqualTo(member);
    assertThat(forAdministrator)
        .as("administering is not reading: the formula's role, never raised to OWNER")
        .singleElement()
        .satisfies(entry -> assertThat(entry.myRole()).isEqualTo(AssetRole.VIEWER));
  }

  @Test
  void theStatusFollowsSuccessionAndTheNewestIndexingRun() {
    UUID leaver = createUser("Ausgeschieden");
    UUID prompts = promptLibrary("Vorlagen", owner, true);
    UUID orphaned = promptLibrary("Verwaist", leaver, true);
    UUID upload = knowledgeLibrary("Hochgeladen", owner, SourceType.UPLOAD);
    UUID neverIndexed = knowledgeLibrary("Nie indiziert", owner, SourceType.of("FILESYSTEM"));
    UUID running = knowledgeLibrary("Läuft", owner, SourceType.of("FILESYSTEM"));
    UUID failed = knowledgeLibrary("Gescheitert", owner, SourceType.of("FILESYSTEM"));
    UUID completed = knowledgeLibrary("Fertig", owner, SourceType.of("FILESYSTEM"));
    Instant done = Instant.parse("2026-09-20T06:00:00Z");
    Instant earlier = done.minus(1, ChronoUnit.HOURS);
    Instant later = done.plus(1, ChronoUnit.HOURS);
    indexingRun(running, JobStatus.COMPLETED, earlier, done);
    indexingRun(running, JobStatus.RUNNING, later, null);
    indexingRun(failed, JobStatus.COMPLETED, earlier, done);
    indexingRun(failed, JobStatus.FAILED, later, null);
    indexingRun(completed, JobStatus.FAILED, earlier, null);
    indexingRun(completed, JobStatus.COMPLETED, later, done);
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", leaver);

    List<AssetCatalogEntry> entries = query(null, null, false).entries();

    assertThat(entryFor(entries, prompts).status()).isEqualTo(AssetCatalogStatus.READY);
    assertThat(entryFor(entries, prompts).facts()).isNull();
    assertThat(entryFor(entries, orphaned).status()).isEqualTo(AssetCatalogStatus.SUCCESSION_OPEN);
    assertThat(entryFor(entries, upload).status()).isEqualTo(AssetCatalogStatus.READY);
    assertThat(entryFor(entries, neverIndexed).status())
        .isEqualTo(AssetCatalogStatus.NOT_YET_AVAILABLE);
    assertThat(entryFor(entries, running).status()).isEqualTo(AssetCatalogStatus.UPDATING);
    assertThat(entryFor(entries, failed).status()).isEqualTo(AssetCatalogStatus.UPDATE_FAILED);
    assertThat(entryFor(entries, completed).status()).isEqualTo(AssetCatalogStatus.READY);
    assertThat(entryFor(entries, failed).facts())
        .isEqualTo(
            new KnowledgeLibraryCatalogFacts("FILESYSTEM", done, AssetCatalogStatus.UPDATE_FAILED));
    assertThat(entryFor(entries, upload).facts())
        .isEqualTo(new KnowledgeLibraryCatalogFacts("UPLOAD", null, AssetCatalogStatus.READY));
  }

  /**
   * What the member may read: two public prompt libraries (one also granted to their group), a
   * public knowledge library, one granted to their group, one owned by their group but granted to
   * them in person, one granted to them directly, and two whose grant to all accounts or to their
   * group has expired. Unreadable for them: a closed one, one granted to and owned by another
   * group, and one owned by their group without any grant reaching them.
   */
  private void createTheMembersCatalog() {
    promptLibrary(PUBLIC, owner, true);
    UUID publicAndGroup = promptLibrary(PUBLIC_AND_GROUP, owner, true);
    grantToGroup(publicAndGroup, myGroup, null);
    knowledgeLibraryPublic(PUBLIC_KNOWLEDGE);
    grantToGroup(promptLibrary(GROUP_GRANT, owner, false), myGroup, null);
    UUID groupOwned = groupOwnedPromptLibrary(GROUP_OWNED, myGroup);
    grantToUser(groupOwned, member, null);
    grantToUser(promptLibrary(DIRECT, owner, false), member, null);
    UUID expiredPublic = promptLibrary(EXPIRED_PUBLIC, owner, false);
    grantRepository.save(
        AssetGrant.forAllAccounts(
            PromptLibrary.ASSET_TYPE,
            expiredPublic,
            organization,
            AssetRole.VIEWER,
            Instant.now().minus(1, ChronoUnit.DAYS),
            owner));
    grantToUser(expiredPublic, member, null);
    UUID expiredGroup = promptLibrary(EXPIRED_GROUP_GRANT, owner, false);
    grantToGroup(expiredGroup, myGroup, Instant.now().minus(1, ChronoUnit.DAYS));
    grantToUser(expiredGroup, member, null);

    promptLibrary("Geschlossen", owner, false);
    UUID foreign = groupOwnedPromptLibrary("Fremde Gruppe", otherGroup);
    grantToGroup(foreign, otherGroup, null);
    groupOwnedPromptLibrary("Gruppeneigentum ohne Recht", myGroup);
  }

  private AssetCatalogPage query(
      AssetType type, AssetCatalogVisibility visibility, boolean fromMyGroups) {
    return catalogService.list(
        callerOf(member),
        new AssetCatalogQuery(type, null, visibility, fromMyGroups, AssetCatalogSort.NAME),
        0,
        200);
  }

  private static AssetCatalogQuery sorted(AssetCatalogSort sort) {
    return new AssetCatalogQuery(null, null, null, false, sort);
  }

  private static List<AssetCatalogVisibility> visibilitiesAndBoth() {
    List<AssetCatalogVisibility> values = new ArrayList<>();
    values.add(null);
    values.addAll(List.of(AssetCatalogVisibility.values()));
    return values;
  }

  private UUID promptLibrary(String name, UUID ownerId, boolean allAccounts) {
    UUID id =
        promptLibraryRepository
            .save(PromptLibrary.ownedByUser(organization, name, null, ownerId))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            PromptLibrary.ASSET_TYPE, id, organization, ownerId, AssetRole.OWNER, null, ownerId));
    if (allAccounts) {
      grantRepository.save(
          AssetGrant.forAllAccounts(
              PromptLibrary.ASSET_TYPE, id, organization, AssetRole.VIEWER, null, ownerId));
    }
    return id;
  }

  /** Owned by the group, deliberately without the group's own grant. */
  private UUID groupOwnedPromptLibrary(String name, UUID groupId) {
    UUID id =
        promptLibraryRepository
            .save(PromptLibrary.ownedByGroup(organization, name, null, groupId))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            PromptLibrary.ASSET_TYPE, id, organization, owner, AssetRole.OWNER, null, owner));
    return id;
  }

  /** Released to all accounts. */
  private UUID knowledgeLibrary(String name, UUID ownerId, SourceType sourceType) {
    UUID id = ownerOnlyKnowledgeLibrary(name, ownerId, sourceType);
    grantRepository.save(
        AssetGrant.forAllAccounts(
            KnowledgeLibrary.ASSET_TYPE, id, organization, AssetRole.VIEWER, null, ownerId));
    return id;
  }

  private UUID ownerOnlyKnowledgeLibrary(String name, UUID ownerId, SourceType sourceType) {
    UUID id =
        knowledgeLibraryRepository
            .save(
                KnowledgeLibrary.ownedByUser(
                    organization, name, null, ownerId, sourceType, null, null, null, null, false))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            id,
            organization,
            ownerId,
            AssetRole.OWNER,
            null,
            ownerId));
    return id;
  }

  private void knowledgeLibraryPublic(String name) {
    knowledgeLibrary(name, owner, SourceType.UPLOAD);
  }

  private void grantToGroup(UUID promptLibraryId, UUID groupId, Instant expiresAt) {
    grantRepository.save(
        AssetGrant.forGroup(
            PromptLibrary.ASSET_TYPE,
            promptLibraryId,
            organization,
            groupId,
            AssetRole.VIEWER,
            expiresAt,
            owner,
            1));
  }

  private void grantToUser(UUID promptLibraryId, UUID userId, Instant expiresAt) {
    grantRepository.save(
        AssetGrant.forUser(
            PromptLibrary.ASSET_TYPE,
            promptLibraryId,
            organization,
            userId,
            AssetRole.VIEWER,
            expiresAt,
            owner));
  }

  private void indexingRun(
      UUID libraryId, JobStatus status, Instant startedAt, Instant completedAt) {
    IndexingJob job = new IndexingJob(status);
    job.setLibraryId(libraryId);
    job.setOrganizationId(organization);
    job.setCompletedAt(completedAt);
    UUID id = indexingJobRepository.saveAndFlush(job).getId();
    jdbcTemplate.update(
        "UPDATE indexing_jobs SET started_at = ? WHERE id = ?", Timestamp.from(startedAt), id);
  }

  private void setUpdatedAt(UUID assetId, Instant updatedAt) {
    jdbcTemplate.update(
        "UPDATE assets SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), assetId);
  }

  private static List<String> names(AssetCatalogPage page) {
    return page.entries().stream().map(entry -> entry.asset().getName()).toList();
  }

  private static AssetCatalogEntry entryFor(List<AssetCatalogEntry> entries, UUID id) {
    return entries.stream()
        .filter(entry -> entry.asset().getId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private UUID createUser(String displayName) {
    User user =
        new User(
            "catalog-filter-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organization);
    return userRepository.save(user).getId();
  }

  private UUID createGroup(String name, UUID... members) {
    Group created =
        new Group(organization, GroupKind.AD_HOC, name, "Ad-hoc-Gruppe", null, null, null, null);
    created.release(true);
    for (UUID memberId : members) {
      created.addMembership(new GroupMembership(memberId, organization));
    }
    return groupRepository.save(created).getId();
  }

  private CurrentUser callerOf(UUID userId) {
    return callerOf(userId, false);
  }

  private CurrentUser callerOf(UUID userId, boolean systemAdmin) {
    return CurrentUser.of(
        userId,
        organization,
        systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER,
        "Sachbearbeitung");
  }
}
