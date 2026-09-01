package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Leitplanke (e): a library is locked by default, and only the responsible owner changes that. The
 * decisive assertion is {@link #anAdministratorCannotLiftAForeignLock} - together with the {@code
 * systemAdmin = false} argument it verifies, it is what keeps an administrative privilege from
 * unlocking a Bestand it is not responsible for.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LibraryDiagnosticsLockServiceTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  @Mock private KnowledgeLibraryRepository libraryRepository;
  @Mock private LibraryAccessService accessService;
  @Mock private AuditEventRecorder auditEventRecorder;

  private LibraryDiagnosticsLockService service;

  private final UUID ownerId = UUID.randomUUID();
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    service =
        new LibraryDiagnosticsLockService(libraryRepository, accessService, auditEventRecorder);
    library =
        KnowledgeLibrary.ownedByUser(
            ORGANIZATION_ID, "Personalvorgänge", null, ownerId, LibraryVisibility.PRIVATE, false);
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(libraryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void aNewLibraryStartsLocked() {
    assertThat(library.isDiagnosticsLocked()).isTrue();
  }

  @Test
  void theOwnerLiftsTheLockAndTheChangeIsRecorded() {
    when(accessService.effectiveRole(library, ownerId, false)).thenReturn(AssetRole.OWNER);

    KnowledgeLibrary saved = service.setLocked(owner(), library.getId(), false);

    assertThat(saved.isDiagnosticsLocked()).isFalse();
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(event.capture());
    assertThat(event.getValue().eventType())
        .isEqualTo(AuditEventType.LIBRARY_DIAGNOSTICS_LOCK_CHANGED);
  }

  @Test
  void anAdministratorCannotLiftAForeignLock() {
    UUID adminId = UUID.randomUUID();
    // The service must resolve the role without the system-admin floor; with it, this stub would
    // never be consulted and the call would succeed.
    when(accessService.effectiveRole(eq(library), eq(adminId), eq(false))).thenReturn(null);

    assertThatThrownBy(
            () ->
                service.setLocked(
                    CurrentUser.of(adminId, ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, "Admin"),
                    library.getId(),
                    false))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(library.isDiagnosticsLocked()).isTrue();
  }

  @Test
  void aManagerIsNotEnoughToLiftTheLock() {
    when(accessService.effectiveRole(library, ownerId, false)).thenReturn(AssetRole.MANAGER);

    assertThatThrownBy(() -> service.setLocked(owner(), library.getId(), false))
        .isInstanceOf(AccessDeniedException.class);
  }

  private CurrentUser owner() {
    return CurrentUser.of(ownerId, ORGANIZATION_ID, SystemRole.USER, "Zuständige Stelle");
  }
}
