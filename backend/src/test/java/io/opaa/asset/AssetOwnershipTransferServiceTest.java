package io.opaa.asset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.permission.SuccessionCaseCloser;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Who may hand an asset to a group of their own. */
class AssetOwnershipTransferServiceTest {

  private final UUID organizationId = UUID.randomUUID();
  private final UUID ownerId = UUID.randomUUID();
  private final CurrentUser owner =
      CurrentUser.of(ownerId, organizationId, SystemRole.USER, "Eigentümerin");

  private AssetAuthorization authorization;
  private AssetShellOwnershipDirectory ownershipDirectory;
  private AssetOwnershipTransferService service;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    authorization = mock(AssetAuthorization.class);
    ownershipDirectory = mock(AssetShellOwnershipDirectory.class);
    service =
        new AssetOwnershipTransferService(
            authorization,
            ownershipDirectory,
            mock(AssetGrantService.class),
            mock(UserRepository.class),
            mock(SuccessionCaseCloser.class),
            mock(OwnerOnlyRule.class));
    library = KnowledgeLibrary.ownedByUser(organizationId, "Akten", null, UUID.randomUUID());
    when(authorization.load(KnowledgeLibrary.ASSET_TYPE, library.getId(), organizationId))
        .thenReturn(library);
    when(authorization.requireRole(library, ownerId, false, AssetRole.OWNER))
        .thenReturn(AssetRole.OWNER);
  }

  // regression guard for #3358 (2): a time-limited owner cannot keep the asset through their group
  @Test
  void aTimeLimitedOwnerCannotHandTheAssetToAGroupOfTheirOwn() {
    UUID group = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.transferOwnership(
                    KnowledgeLibrary.ASSET_TYPE,
                    library.getId(),
                    AssetOwnerType.GROUP,
                    group,
                    owner))
        .isInstanceOf(AccessDeniedException.class);
    verify(ownershipDirectory, never()).handOver(any(), any(), any());
  }

  @Test
  void anOwnerWithoutExpiryHandsTheAssetToAGroupOfTheirOwn() {
    UUID group = UUID.randomUUID();
    when(authorization.holdsUntil(library, ownerId, false, AssetRole.MANAGER, null))
        .thenReturn(true);

    service.transferOwnership(
        KnowledgeLibrary.ASSET_TYPE, library.getId(), AssetOwnerType.GROUP, group, owner);

    verify(ownershipDirectory).handOver(any(), any(), any());
  }
}
