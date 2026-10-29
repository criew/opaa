package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.account.AccountActivityService;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.Asset;
import io.opaa.asset.AssetAuthorization;
import io.opaa.asset.AssetRepository;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.asset.AssetTypes;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.notification.NotificationService;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.GroupCapabilityService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.SuccessionReachGuard;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Detaching tells a non-member nothing about a space the asset is not associated with. */
class SpaceAssetDetachTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private final SpaceRepository spaceRepository = mock(SpaceRepository.class);
  private final SpaceAssetAssociationRepository associations =
      mock(SpaceAssetAssociationRepository.class);
  private final AssetRepository assetRepository = mock(AssetRepository.class);
  private final AssetAuthorization assetAuthorization = mock(AssetAuthorization.class);
  private final AssetTypes assetTypes = mock(AssetTypes.class);
  private final AccountActivityService accountActivity = mock(AccountActivityService.class);
  private SpaceAssetAssociationService service;
  private Space space;
  private KnowledgeLibrary library;
  private final UUID managerId = UUID.randomUUID();
  private final CurrentUser manager =
      CurrentUser.of(managerId, ORGANIZATION, SystemRole.USER, "Verwaltende");

  @BeforeEach
  void setUp() {
    when(accountActivity.activeAmong(anyCollection()))
        .thenAnswer(invocation -> Set.copyOf(invocation.getArgument(0, Collection.class)));
    GroupMembershipResolver groups = mock(GroupMembershipResolver.class);
    SpaceAccessPolicy policy =
        new SpaceAccessPolicy(
            groups,
            mock(GroupSubjectDirectory.class),
            mock(GroupCapabilityService.class),
            accountActivity,
            mock(SpaceMembershipRepository.class));
    service =
        new SpaceAssetAssociationService(
            mock(SuccessionReachGuard.class),
            associations,
            spaceRepository,
            assetRepository,
            assetAuthorization,
            mock(AssetAccessService.class),
            assetTypes,
            mock(UserRepository.class),
            groups,
            policy,
            mock(AuditEventRecorder.class),
            mock(NotificationService.class));

    UUID owner = UUID.randomUUID();
    space = new Space("Fremder Space", null, false, owner, ORGANIZATION);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, ORGANIZATION));
    when(spaceRepository.findByIdWithMemberships(space.getId())).thenReturn(Optional.of(space));

    library = KnowledgeLibrary.ownedByUser(ORGANIZATION, "Akten", null, managerId);
    when(assetRepository.findById(library.getId())).thenReturn(Optional.of((Asset) library));
    when(assetTypes.isActive(any())).thenReturn(true);
    AssetTypeDefinition definition = mock(AssetTypeDefinition.class);
    when(definition.auditObjectType()).thenReturn(AuditObjectType.KNOWLEDGE_LIBRARY);
    when(assetTypes.require(library.getAssetType())).thenReturn(definition);
    when(assetAuthorization.load(library.getAssetType(), library.getId(), ORGANIZATION))
        .thenReturn(library);
    when(assetAuthorization.canManage(any(), any(), anyBoolean())).thenReturn(true);
  }

  // regression guard for #3358 (4): a 204 for an existing space would confirm that it exists
  @Test
  void aManagerWhoIsNoMemberLearnsNothingAboutASpaceWithoutTheAssociation() {
    when(associations.findBySpaceIdAndAssetId(space.getId(), library.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.detach(space.getId(), library.getId(), manager))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aManagerWhoIsNoMemberStillDetachesAnExistingAssociation() {
    when(associations.findBySpaceIdAndAssetId(space.getId(), library.getId()))
        .thenReturn(
            Optional.of(
                new SpaceAssetAssociation(
                    space.getId(), library.getId(), ORGANIZATION, managerId)));

    assertThatCode(() -> service.detach(space.getId(), library.getId(), manager))
        .doesNotThrowAnyException();
  }
}
