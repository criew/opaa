package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.library.LibraryAccessService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The guarded execution path: befugnis, Begründung, Diagnosesperre, protocol, and the guarantee
 * that a run without a written entry is not reachable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForeignDiagnosticContextServiceTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  @Mock private DiagnosticImpersonationGrantService grantService;
  @Mock private LibraryDiagnosticsLockService lockService;
  @Mock private LibraryAccessService libraryAccessService;
  @Mock private UserRepository userRepository;
  @Mock private AuditActorPseudonymService pseudonymService;
  @Mock private DiagnosticContextLogRepository logRepository;

  private ForeignDiagnosticContextService service;

  private final UUID actorId = UUID.randomUUID();
  private final UUID targetId = UUID.randomUUID();
  private final UUID openLibrary = UUID.randomUUID();
  private final UUID lockedLibrary = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new ForeignDiagnosticContextService(
            grantService,
            lockService,
            libraryAccessService,
            userRepository,
            pseudonymService,
            logRepository);
    when(pseudonymService.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    when(userRepository.findByIdAndOrganizationId(any(), any()))
        .thenReturn(Optional.of(new User("s", "i", null, "Zielperson")));
    when(libraryAccessService.readableLibraryIds(targetId, ORGANIZATION_ID))
        .thenReturn(Set.of(openLibrary, lockedLibrary));
    when(lockService.lockedAmong(any())).thenReturn(Set.of(lockedLibrary));
    when(logRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void refusesAPersonContextWithoutAJustificationBeforeTouchingAnyRights() {
    AtomicBoolean executed = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                service.execute(
                    actor(),
                    ForeignDiagnosticRequest.forUser(targetId, "Wo steht das?", "   "),
                    context -> {
                      executed.set(true);
                      return new ForeignDiagnosticFindings<>(List.of(), "x");
                    }))
        .isInstanceOf(ValidationException.class);

    assertThat(executed).isFalse();
    verify(grantService, never()).requireImpersonationPermission(any(), any());
    verify(logRepository, never()).save(any());
  }

  @Test
  void refusesAPersonContextWithoutTheBefugnis() {
    when(grantService.requireImpersonationPermission(any(), any()))
        .thenThrow(new AccessDeniedException("keine Befugnis"));

    assertThatThrownBy(
            () ->
                service.execute(
                    actor(),
                    ForeignDiagnosticRequest.forUser(targetId, "Wo steht das?", "Beschwerde 4711"),
                    context -> new ForeignDiagnosticFindings<>(List.of(), "x")))
        .isInstanceOf(AccessDeniedException.class);
    verify(logRepository, never()).save(any());
  }

  @Test
  void removesDiagnosegesperrteBibliothekenFromTheSearchableSetAndNamesThem() {
    ForeignDiagnosticOutcome<String> outcome =
        service.execute(
            actor(),
            ForeignDiagnosticRequest.forUser(targetId, "Wo steht das?", "Beschwerde 4711"),
            context -> new ForeignDiagnosticFindings<>(List.of("chunk-1"), "Anzeige"));

    assertThat(outcome.context().searchableLibraryIds()).containsExactly(openLibrary);
    assertThat(outcome.context().lockedLibraryIds()).containsExactly(lockedLibrary);
    assertThat(outcome.presentation()).isEqualTo("Anzeige");
  }

  @Test
  void writesOneProtocolEntryPerExecutionWithTheMandatoryFields() {
    service.execute(
        actor(),
        ForeignDiagnosticRequest.forUser(
            targetId, "Wo steht die Dienstanweisung?", "Beschwerde 4711"),
        context -> new ForeignDiagnosticFindings<>(List.of("chunk-1", "chunk-2"), "Anzeige"));

    ArgumentCaptor<DiagnosticContextLogEntry> entry =
        ArgumentCaptor.forClass(DiagnosticContextLogEntry.class);
    verify(logRepository).save(entry.capture());
    DiagnosticContextLogEntry written = entry.getValue();
    assertThat(written.getTargetKind()).isEqualTo(DiagnosticTargetKind.USER);
    assertThat(written.getTestQuestion()).isEqualTo("Wo steht die Dienstanweisung?");
    assertThat(written.getHitCount()).isEqualTo(2);
    assertThat(written.getHitRefs()).isEqualTo("chunk-1,chunk-2");
    assertThat(written.getJustification()).isEqualTo("Beschwerde 4711");
    assertThat(written.getPermissionSnapshot())
        .isEqualTo("libraries=[" + openLibrary + "];lockedLibraries=[" + lockedLibrary + "]");
  }

  @Test
  void aProfileContextNeedsNoBefugnisAndNoJustificationButIsStillRecorded() {
    ForeignDiagnosticOutcome<String> outcome =
        service.execute(
            actor(),
            ForeignDiagnosticRequest.forProfile(
                "Sachbearbeitung Bauamt", Set.of(openLibrary, lockedLibrary), "Wo steht das?"),
            context -> new ForeignDiagnosticFindings<>(List.of("chunk-1"), "Anzeige"));

    verify(grantService, never()).requireImpersonationPermission(any(), any());
    ArgumentCaptor<DiagnosticContextLogEntry> entry =
        ArgumentCaptor.forClass(DiagnosticContextLogEntry.class);
    verify(logRepository).save(entry.capture());
    assertThat(entry.getValue().getTargetKind()).isEqualTo(DiagnosticTargetKind.PERMISSION_PROFILE);
    assertThat(entry.getValue().getTargetRef()).isEqualTo("Sachbearbeitung Bauamt");
    assertThat(entry.getValue().getJustification()).isNull();
    assertThat(outcome.logEntryId()).isNotNull();
  }

  @Test
  void refusesToTreatTheCallersOwnContextAsAForeignOne() {
    assertThatThrownBy(
            () ->
                service.execute(
                    actor(),
                    ForeignDiagnosticRequest.forUser(actorId, "Wo steht das?", "Selbsttest"),
                    context -> new ForeignDiagnosticFindings<>(List.of(), "x")))
        .isInstanceOf(ValidationException.class);
    verify(logRepository, never()).save(any());
  }

  private CurrentUser actor() {
    return CurrentUser.of(actorId, ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, "Diagnostizierende");
  }
}
