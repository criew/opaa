package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Resolving a transfer asks every ownership directory whether the target can take over. */
class PermissionTransferResolveTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private final UserRepository users = mock(UserRepository.class);
  private final AssetOwnershipDirectory spaces = mock(AssetOwnershipDirectory.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final PermissionTransferService service =
      new PermissionTransferService(
          mock(PermissionTransferRepository.class),
          mock(PermissionTransferObjectRepository.class),
          mock(AssetGrantRepository.class),
          mock(CapabilityGrantRepository.class),
          mock(GroupSubjectDirectory.class),
          mock(GroupSpaceMembershipDirectory.class),
          mock(GroupStewardshipDirectory.class),
          List.of(spaces),
          mock(PermissionHistoryService.class),
          mock(PermissionHistoryClock.class),
          Clock.systemUTC(),
          mock(AssetAccessService.class),
          users,
          audit,
          mock(SuccessionCaseCloser.class),
          mock(PermissionTransferPreviewToken.class));

  private UUID person() {
    User user = new User(UUID.randomUUID().toString(), "issuer", "p@example.org", "Person");
    user.setOrganizationId(ORGANIZATION);
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    return user.getId();
  }

  // regression guard for #3358 (4): the preview names an inactive successor of spaces
  @Test
  void thePreviewRefusesATargetTheOwnershipDirectoryRefuses() {
    UUID source = person();
    UUID target = person();
    doThrow(new ConflictException("Das ausgewählte Konto ist nicht aktiv"))
        .when(spaces)
        .requireAcceptableNewOwner(
            PermissionSubject.user(source, ORGANIZATION),
            PermissionSubject.user(target, ORGANIZATION));
    CurrentUser admin =
        CurrentUser.of(UUID.randomUUID(), ORGANIZATION, SystemRole.SYSTEM_ADMIN, "A");

    assertThatThrownBy(
            () ->
                service.preview(
                    new PermissionTransferOrder(
                        PermissionSubjectType.USER,
                        source,
                        PermissionSubjectType.USER,
                        target,
                        Set.of(PermissionTransferScope.OWNERSHIP)),
                    admin))
        .isInstanceOf(ConflictException.class);
    verify(spaces, never()).assetIdsOwnedBy(any());
  }
}
