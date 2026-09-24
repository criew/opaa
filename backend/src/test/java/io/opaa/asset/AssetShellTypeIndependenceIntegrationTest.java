package io.opaa.asset;

import static io.opaa.test.TestPromptLibraryAssetType.PROMPT_LIBRARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AccessPath;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.SuccessionFinding;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationRepository;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceAssetLink;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.succession.SuccessionCaseRepository;
import io.opaa.succession.SuccessionDetectionService;
import io.opaa.succession.SuccessionService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The acceptance criteria of #1899 and #1900 for a second asset type: {@code PROMPT_LIBRARY},
 * declared by the tests alone ({@code TestPromptLibraryAssetType}), gets grants, the
 * organization-wide release, the Herleitung, a reach change with its history and a space
 * association from the one shell, and the succession rules of an asset without a capable owner -
 * without a table, an entity or a line of logic of its own. The search keeps reading knowledge
 * libraries only.
 */
@OpaaIntegrationTest
class AssetShellTypeIndependenceIntegrationTest {

  @Autowired private AssetGrantService grantService;
  @Autowired private AssetAccessService accessService;
  @Autowired private AssetAccessDerivationService derivationService;
  @Autowired private AssetShellService shellService;
  @Autowired private AssetRepository assetRepository;
  @Autowired private AssetVisibilityHistoryRepository visibilityHistoryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private SpaceAssetAssociationService associationService;
  @Autowired private SpaceAssetAssociationRepository associationRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SuccessionDetectionService detectionService;
  @Autowired private SuccessionService successionService;
  @Autowired private SuccessionCaseRepository successionCases;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID organizationId;
  private UUID owner;
  private UUID reader;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Asset-Schale " + UUID.randomUUID()))
            .getId();
    owner = createUser("Eigentuemerin");
    reader = createUser("Leserin");
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void aGrantOnATestDefinedTypeIsWrittenListedEvaluatedAndRevokedByTheOneGrantService() {
    UUID prompts = createPromptLibrary(AssetVisibility.PRIVATE);

    var view =
        grantService.upsertGrant(
            PROMPT_LIBRARY,
            prompts,
            new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.EDITOR),
            callerOf(owner));

    assertThat(view.grant().getAssetType()).isEqualTo(PROMPT_LIBRARY);
    assertThat(grantService.listGrants(PROMPT_LIBRARY, prompts, callerOf(owner)))
        .extracting(listed -> listed.grant().getSubjectId())
        .containsExactlyInAnyOrder(owner, reader);
    assertThat(accessService.readableAssetIds(PROMPT_LIBRARY, reader, organizationId))
        .containsExactly(prompts);
    assertThat(accessService.effectiveRole(PROMPT_LIBRARY, prompts, reader, false))
        .isEqualTo(AssetRole.EDITOR);

    grantService.revokeGrant(PROMPT_LIBRARY, prompts, view.grant().getId(), callerOf(owner));

    assertThat(accessService.readableAssetIds(PROMPT_LIBRARY, reader, organizationId)).isEmpty();
  }

  @Test
  void anOrganizationWideReleaseOfATestDefinedTypeReachesEverybodyAndIsHistorised() {
    UUID prompts = createPromptLibrary(AssetVisibility.PRIVATE);
    assertThat(accessService.readableAssetIds(PROMPT_LIBRARY, reader, organizationId)).isEmpty();

    transactionTemplate.executeWithoutResult(
        status -> {
          Asset asset = assetRepository.findById(prompts).orElseThrow();
          assertThat(asset)
              .as("a type without an entity loads as the shell")
              .isExactlyInstanceOf(Asset.class);
          shellService.changeReach(asset, AssetVisibility.ORGANIZATION, false, owner);
        });

    assertThat(accessService.readableAssetIds(PROMPT_LIBRARY, reader, organizationId))
        .containsExactly(prompts);
    assertThat(accessService.readableAssetIds(KnowledgeLibrary.ASSET_TYPE, reader, organizationId))
        .doesNotContain(prompts);
    AssetAccessDerivation derivation =
        derivationService.derive(PROMPT_LIBRARY, prompts, callerOf(reader));
    assertThat(derivation.effectiveRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(derivation.paths())
        .extracting(AccessPath::basis)
        .containsExactly(AccessBasis.ORGANIZATION_WIDE);
    assertThat(
            visibilityHistoryRepository.findByAssetTypeAndAssetIdAndValidToIsNull(
                PROMPT_LIBRARY, prompts))
        .get()
        .satisfies(
            interval -> {
              assertThat(interval.getVisibility()).isEqualTo(AssetVisibility.ORGANIZATION);
              assertThat(interval.getCause())
                  .isEqualTo(AssetVisibilityHistoryCause.VISIBILITY_CHANGED);
            });
  }

  @Test
  void aTestDefinedTypeIsAssociatedAndDetachedButTheSearchReadsLibrariesOnly() {
    UUID prompts = createPromptLibrary(AssetVisibility.PRIVATE);
    UUID library =
        libraryRepository
            .save(
                KnowledgeLibrary.ownedByUser(
                    organizationId, "Bibliothek", null, owner, AssetVisibility.PRIVATE, false))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            library,
            organizationId,
            owner,
            AssetRole.OWNER,
            null,
            owner));
    UUID space = createSpace(owner);

    SpaceAssetLink associated =
        associationService.associate(space, PROMPT_LIBRARY, prompts, callerOf(owner));
    assertThat(associationService.listForSpace(space, callerOf(owner)).narrowsSearch())
        .as("an asset without documents narrows no search")
        .isFalse();
    associationService.associate(space, KnowledgeLibrary.ASSET_TYPE, library, callerOf(owner));

    assertThat(associated.assetType()).isEqualTo(PROMPT_LIBRARY);
    assertThat(associationService.listForSpace(space, callerOf(owner)).items())
        .extracting(SpaceAssetLink::assetType)
        .containsExactlyInAnyOrder(PROMPT_LIBRARY, KnowledgeLibrary.ASSET_TYPE);
    assertThat(associationService.libraryIdsInSpace(space)).containsExactly(library);
    assertThat(associationService.listForSpace(space, callerOf(owner)).narrowsSearch()).isTrue();
    assertThat(associationRepository.findLibraryIdsBySpaceId(space)).containsExactly(library);

    associationService.detach(space, prompts, callerOf(owner));

    assertThat(associationRepository.existsBySpaceIdAndAssetId(space, prompts)).isFalse();
    assertThat(associationRepository.existsBySpaceIdAndAssetId(space, library)).isTrue();
  }

  /**
   * ADR-0036 Entscheidung 6 for a type no enum names: without a capable owner it is listed, the run
   * records it, and its reach is frozen for a new grant, a wider release and a new association.
   */
  @Test
  void aTestDefinedTypeWithoutACapableOwnerIsListedRecordedAndFrozen() {
    UUID prompts = createPromptLibrary(AssetVisibility.PRIVATE);
    grantRepository.save(
        AssetGrant.forUser(
            PROMPT_LIBRARY, prompts, organizationId, reader, AssetRole.VIEWER, null, owner));
    UUID space = createSpace(reader);
    UUID administrator = createUser("Systemverwaltung");
    CurrentUser systemAdmin =
        CurrentUser.of(administrator, organizationId, SystemRole.SYSTEM_ADMIN, "Systemverwaltung");
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", owner);

    detectionService.runFor(organizationId);

    assertThat(successionService.findingForAsset(PROMPT_LIBRARY, prompts))
        .get()
        .extracting(SuccessionFinding::assetType)
        .isEqualTo(PROMPT_LIBRARY);
    assertThat(
            successionService.list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50).entries())
        .extracting(entry -> entry.finding().objectId())
        .contains(prompts);
    assertThat(
            successionCases.findByKindAndObjectIdAndClosedAtIsNull(
                SuccessionKind.OPEN_SUCCESSION, prompts))
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.getObjectType()).isEqualTo(SuccessionObjectType.ASSET);
              assertThat(recorded.getAssetType()).isEqualTo(PROMPT_LIBRARY);
            });

    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    PROMPT_LIBRARY,
                    prompts,
                    new AssetGrantUpsert(
                        PermissionSubjectType.USER, administrator, AssetRole.VIEWER),
                    systemAdmin))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        shellService.changeReach(
                            assetRepository.findById(prompts).orElseThrow(),
                            AssetVisibility.ORGANIZATION,
                            false,
                            administrator)))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () -> associationService.associate(space, PROMPT_LIBRARY, prompts, callerOf(reader)))
        .isInstanceOf(ConflictException.class);
  }

  /** The grants and associations of any type go with their asset - the foreign keys of #1899. */
  @Test
  void deletingTheShellTakesGrantsAndAssociationsOfEveryTypeWithIt() {
    UUID prompts = createPromptLibrary(AssetVisibility.PRIVATE);
    UUID space = createSpace(owner);
    associationService.associate(space, PROMPT_LIBRARY, prompts, callerOf(owner));

    jdbcTemplate.update("DELETE FROM assets WHERE id = ?", prompts);

    assertThat(grantRepository.findByAssetTypeAndAssetId(PROMPT_LIBRARY, prompts)).isEmpty();
    assertThat(associationRepository.existsBySpaceIdAndAssetId(space, prompts)).isFalse();
  }

  /** A prompt library as its later issue will hold one: a shell row and its owner's grant. */
  private UUID createPromptLibrary(AssetVisibility visibility) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " visibility, created_by_user_id) VALUES (?, ?, ?, 'Prompts', 'USER', ?, ?, ?)",
        id,
        PROMPT_LIBRARY.value(),
        organizationId,
        owner,
        visibility.name(),
        owner);
    grantRepository.save(
        AssetGrant.forUser(
            PROMPT_LIBRARY, id, organizationId, owner, AssetRole.OWNER, null, owner));
    return id;
  }

  private UUID createSpace(UUID admin) {
    Space space = new Space("Prompts", null, false, SpaceVisibility.PRIVATE, admin, organizationId);
    space.addMembership(SpaceMembership.ofUser(admin, SpaceRole.ADMIN, organizationId));
    return spaceRepository.save(space).getId();
  }

  private UUID createUser(String displayName) {
    User user =
        new User(
            "asset-shell-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private CurrentUser callerOf(UUID userId) {
    return CurrentUser.of(userId, organizationId, SystemRole.USER, "Sachbearbeitung");
  }
}
