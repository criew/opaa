package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
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
  @Mock private GroupRepository groupRepository;
  @Mock private AuditActorPseudonymService pseudonymService;
  @Mock private DiagnosticContextLogWriter logWriter;

  private ForeignDiagnosticContextService service;

  private final UUID actorId = UUID.randomUUID();
  private final UUID targetId = UUID.randomUUID();
  private final UUID openLibrary = UUID.randomUUID();
  private final UUID lockedLibrary = UUID.randomUUID();
  private final Group profileGroup =
      new Group(ORGANIZATION_ID, GroupKind.AD_HOC, "Sachbearbeitung Bauamt", null, null, null);

  @BeforeEach
  void setUp() {
    service =
        new ForeignDiagnosticContextService(
            grantService,
            lockService,
            libraryAccessService,
            userRepository,
            groupRepository,
            pseudonymService,
            logWriter);
    when(pseudonymService.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    when(userRepository.findByIdAndOrganizationId(any(), any()))
        .thenReturn(Optional.of(new User("s", "i", null, "Zielperson")));
    when(libraryAccessService.readableLibraryIds(targetId, ORGANIZATION_ID))
        .thenReturn(Set.of(openLibrary, lockedLibrary));
    when(lockService.lockedAmong(any())).thenReturn(Set.of(lockedLibrary));
    when(logWriter.record(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(libraryAccessService.readableLibraryIds(actorId, ORGANIZATION_ID))
        .thenReturn(Set.of(openLibrary, lockedLibrary));
    when(groupRepository.findById(profileGroup.getId())).thenReturn(Optional.of(profileGroup));
    when(libraryAccessService.readableLibraryIdsForGroup(profileGroup.getId(), ORGANIZATION_ID))
        .thenReturn(Set.of(openLibrary, lockedLibrary));
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
    verify(logWriter, never()).record(any());
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
    verify(logWriter, never()).record(any());
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
    verify(logWriter).record(entry.capture());
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
            ForeignDiagnosticRequest.forProfile(profileGroup.getId(), "Wo steht das?"),
            context -> new ForeignDiagnosticFindings<>(List.of("chunk-1"), "Anzeige"));

    verify(grantService, never()).requireImpersonationPermission(any(), any());
    ArgumentCaptor<DiagnosticContextLogEntry> entry =
        ArgumentCaptor.forClass(DiagnosticContextLogEntry.class);
    verify(logWriter).record(entry.capture());
    assertThat(entry.getValue().getTargetKind()).isEqualTo(DiagnosticTargetKind.PERMISSION_PROFILE);
    // The profile's identifier, never a free text a caller could put a person's name into.
    assertThat(entry.getValue().getTargetRef()).isEqualTo(profileGroup.getId().toString());
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
    verify(logWriter, never()).record(any());
  }

  /**
   * Leitplanke (c) exempts a profile from befugnis and Begründung on the premise that it shows
   * nothing the executing person may not see anyway. The premise is enforced, not assumed: a
   * library outside the caller's own readable set - which is organization-scoped, so this covers an
   * organization-foreign one too - is rejected before anything runs.
   */
  @Test
  void refusesAProfileNamingALibraryTheCallerMayNotReadThemselves() {
    UUID foreignLibrary = UUID.randomUUID();
    when(libraryAccessService.readableLibraryIdsForGroup(profileGroup.getId(), ORGANIZATION_ID))
        .thenReturn(Set.of(openLibrary, foreignLibrary));
    AtomicBoolean executed = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                service.execute(
                    actor(),
                    ForeignDiagnosticRequest.forProfile(profileGroup.getId(), "Wo steht das?"),
                    context -> {
                      executed.set(true);
                      return new ForeignDiagnosticFindings<>(List.of(), "x");
                    }))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(executed).isFalse();
    verify(logWriter, never()).record(any());
  }

  /**
   * The context is handed to the callback before the entry is written, so a callback that keeps the
   * context and then throws must not escape the protocol - otherwise "es gibt keinen Weg, einen
   * Kontext zu erhalten, ohne dass ein Eintrag entsteht" would hold only for callbacks that return
   * normally.
   */
  @Test
  void recordsTheExecutionEvenWhenTheCallbackThrowsAfterSeeingTheContext() {
    assertThatThrownBy(
            () ->
                service.execute(
                    actor(),
                    ForeignDiagnosticRequest.forUser(targetId, "Wo steht das?", "Beschwerde 4711"),
                    context -> {
                      throw new IllegalStateException("die Anzeige ist schon raus");
                    }))
        .isInstanceOf(IllegalStateException.class);

    ArgumentCaptor<DiagnosticContextLogEntry> entry =
        ArgumentCaptor.forClass(DiagnosticContextLogEntry.class);
    verify(logWriter).record(entry.capture());
    assertThat(entry.getValue().getHitCount()).isZero();
    assertThat(entry.getValue().getJustification()).isEqualTo("Beschwerde 4711");
  }

  /**
   * A profile of a foreign organization is not reachable, and it is not distinguishable from an
   * unknown one - the profile's own id is the target reference, so resolving it is a permission
   * step, not a lookup convenience.
   */
  @Test
  void refusesAProfileOfAForeignOrganization() {
    Group foreign =
        new Group(UUID.randomUUID(), GroupKind.AD_HOC, "Fremde Gruppe", null, null, null);
    when(groupRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

    assertThatThrownBy(
            () ->
                service.execute(
                    actor(),
                    ForeignDiagnosticRequest.forProfile(foreign.getId(), "Wo steht das?"),
                    context -> new ForeignDiagnosticFindings<>(List.of(), "x")))
        .isInstanceOf(NotFoundException.class);
    verify(logWriter, never()).record(any());
  }

  /**
   * hitCount stays the number of hits displayed; an oversized identifier list is cut between two
   * identifiers and names how many it left out. Without the count in the marker the two fields
   * would diverge silently at exactly the entries where the diagnosis saw the most.
   */
  @Test
  void namesHowManyIdentifiersTheTruncationLeftOutInsteadOfCuttingSilently() {
    List<String> hitRefs =
        java.util.stream.IntStream.range(0, 1000)
            .mapToObj(index -> String.format("chunk-%015d", index))
            .toList();

    service.execute(
        actor(),
        ForeignDiagnosticRequest.forUser(targetId, "Wo steht das?", "Beschwerde 4711"),
        context -> new ForeignDiagnosticFindings<>(hitRefs, "Anzeige"));

    ArgumentCaptor<DiagnosticContextLogEntry> entry =
        ArgumentCaptor.forClass(DiagnosticContextLogEntry.class);
    verify(logWriter).record(entry.capture());
    DiagnosticContextLogEntry written = entry.getValue();
    assertThat(written.getHitCount()).isEqualTo(1000);

    List<String> recorded = List.of(written.getHitRefs().split(","));
    String marker = recorded.getLast();
    int omitted = Integer.parseInt(marker.substring("…(+".length(), marker.length() - 1));
    assertThat(recorded).hasSizeGreaterThan(1);
    assertThat(recorded.subList(0, recorded.size() - 1))
        .as("truncation cuts between identifiers, never inside one")
        .allSatisfy(ref -> assertThat(hitRefs).contains(ref))
        .hasSize(written.getHitCount() - omitted);
  }

  private CurrentUser actor() {
    return CurrentUser.of(actorId, ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, "Diagnostizierende");
  }
}
