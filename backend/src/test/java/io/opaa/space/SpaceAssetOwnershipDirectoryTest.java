package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ConflictException;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.PermissionSubject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A succession hands a space only to somebody who can answer for it. */
class SpaceAssetOwnershipDirectoryTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private final SpaceRepository spaceRepository = mock(SpaceRepository.class);
  private final SpaceAccessPolicy accessPolicy = mock(SpaceAccessPolicy.class);
  private SpaceAssetOwnershipDirectory directory;
  private Space space;
  private final UUID successor = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    directory =
        new SpaceAssetOwnershipDirectory(
            spaceRepository,
            mock(SpaceMembershipRepository.class),
            mock(SpaceMembershipHistoryService.class),
            mock(AssetOwnershipHistoryService.class),
            // the audit entry names both owners by a reference the recorder hands out
            mock(
                AuditEventRecorder.class,
                invocation ->
                    invocation.getMethod().getReturnType() == UUID.class
                        ? UUID.randomUUID()
                        : RETURNS_DEFAULTS.answer(invocation)),
            accessPolicy);
    space = new Space("Team", "Team docs", false, UUID.randomUUID(), ORGANIZATION);
    when(spaceRepository.findByIdWithMemberships(space.getId())).thenReturn(Optional.of(space));
  }

  private void handOver() {
    directory.transferOwnership(
        Space.ASSET_TYPE,
        space.getId(),
        PermissionSubject.user(successor, ORGANIZATION),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Instant.now());
  }

  // regression guard for #3358 (4): an inactive account does not become a space's owner
  @Test
  void aSuccessionDoesNotHandASpaceToAnInactiveAccount() {
    when(accessPolicy.isActiveAccount(successor)).thenReturn(false);

    assertThatThrownBy(this::handOver)
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("nicht aktiv");
    verify(spaceRepository, never()).save(any());
  }

  @Test
  void anArchivedSpaceGoesAlongWithTheSuccession() {
    when(accessPolicy.isActiveAccount(successor)).thenReturn(true);
    space.archive();

    handOver();

    assertThat(space.getOwnerId()).isEqualTo(successor);
    verify(spaceRepository).save(space);
  }
}
