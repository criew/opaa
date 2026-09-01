package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.library.LibraryAccessService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one entry point for running a search diagnosis in a foreign rights context. Every rule of
 * Leitplanken (c)-(g) and (j) is enforced here, in this order: befugnis, justification,
 * Diagnosesperre, execution, protocol.
 *
 * <p>The callback shape is the reason this is a service and not a set of check methods: a caller
 * cannot obtain a {@link ForeignDiagnosticContext} without also handing back its {@link
 * ForeignDiagnosticFindings}, and the protocol entry is written from those findings before this
 * method returns. "Ein Protokolleintrag je Ausfuehrung" is therefore structural, not a rule a
 * future call site could forget.
 *
 * <p>Not covered here on purpose: a diagnosis in the caller's own rights context. It is not a
 * foreign context, needs no befugnis and produces no protocol entry (Leitplanke (c), last bullet) -
 * a caller running one must not route it through this class.
 */
@Service
public class ForeignDiagnosticContextService {

  private static final int MAX_QUESTION_LENGTH = 2000;
  private static final int MAX_JUSTIFICATION_LENGTH = 1000;
  private static final int MAX_HIT_REFS_LENGTH = 8000;

  private final DiagnosticImpersonationGrantService grantService;
  private final LibraryDiagnosticsLockService lockService;
  private final LibraryAccessService libraryAccessService;
  private final UserRepository userRepository;
  private final AuditActorPseudonymService pseudonymService;
  private final DiagnosticContextLogRepository logRepository;

  public ForeignDiagnosticContextService(
      DiagnosticImpersonationGrantService grantService,
      LibraryDiagnosticsLockService lockService,
      LibraryAccessService libraryAccessService,
      UserRepository userRepository,
      AuditActorPseudonymService pseudonymService,
      DiagnosticContextLogRepository logRepository) {
    this.grantService = grantService;
    this.lockService = lockService;
    this.libraryAccessService = libraryAccessService;
    this.userRepository = userRepository;
    this.pseudonymService = pseudonymService;
    this.logRepository = logRepository;
  }

  /**
   * Runs {@code execution} in the requested foreign context and records the execution.
   *
   * @throws ValidationException if a person context carries no free-text justification, if the
   *     caller names themselves as the target, or if the request is otherwise malformed
   * @throws io.opaa.common.AccessDeniedException if the caller holds no valid "Sicht als" befugnis
   *     covering the target person's Organisationseinheit
   */
  @Transactional
  public <T> ForeignDiagnosticOutcome<T> execute(
      CurrentUser actor,
      ForeignDiagnosticRequest request,
      Function<ForeignDiagnosticContext, ForeignDiagnosticFindings<T>> execution) {
    String question = requireText(request.testQuestion(), "Testfrage", MAX_QUESTION_LENGTH);

    return switch (request.targetKind()) {
      case USER -> executeForUser(actor, request, question, execution);
      case PERMISSION_PROFILE -> executeForProfile(actor, request, question, execution);
    };
  }

  private <T> ForeignDiagnosticOutcome<T> executeForUser(
      CurrentUser actor,
      ForeignDiagnosticRequest request,
      String question,
      Function<ForeignDiagnosticContext, ForeignDiagnosticFindings<T>> execution) {
    UUID targetUserId = request.targetUserId();
    if (targetUserId == null) {
      throw new ValidationException("Für „Sicht als (Person)“ fehlt die Zielperson");
    }
    if (targetUserId.equals(actor.id())) {
      throw new ValidationException(
          "Die Diagnose im eigenen Rechtekontext läuft nicht über „Sicht als“");
    }
    // Leitplanke (d): checked before anything is resolved or read, so a request without a
    // justification never touches the target person's rights at all.
    String justification =
        requireText(request.justification(), "Begründung", MAX_JUSTIFICATION_LENGTH);
    grantService.requireImpersonationPermission(actor, targetUserId);
    userRepository
        .findByIdAndOrganizationId(targetUserId, actor.organizationId())
        .orElseThrow(() -> new NotFoundException("Nutzer nicht gefunden"));

    Set<UUID> candidates =
        libraryAccessService.readableLibraryIds(targetUserId, actor.organizationId());
    return run(
        actor,
        DiagnosticTargetKind.USER,
        pseudonymService.pseudonymFor(targetUserId, actor.organizationId()).toString(),
        candidates,
        question,
        justification,
        execution);
  }

  private <T> ForeignDiagnosticOutcome<T> executeForProfile(
      CurrentUser actor,
      ForeignDiagnosticRequest request,
      String question,
      Function<ForeignDiagnosticContext, ForeignDiagnosticFindings<T>> execution) {
    if (request.targetUserId() != null) {
      throw new ValidationException("Ein Rechteprofil trägt keine Zielperson");
    }
    String label = requireText(request.profileLabel(), "Bezeichnung des Rechteprofils", 255);
    Set<UUID> candidates =
        request.profileLibraryIds() == null ? Set.of() : Set.copyOf(request.profileLibraryIds());
    return run(
        actor,
        DiagnosticTargetKind.PERMISSION_PROFILE,
        label,
        candidates,
        question,
        null,
        execution);
  }

  private <T> ForeignDiagnosticOutcome<T> run(
      CurrentUser actor,
      DiagnosticTargetKind targetKind,
      String targetRef,
      Set<UUID> candidateLibraryIds,
      String question,
      String justification,
      Function<ForeignDiagnosticContext, ForeignDiagnosticFindings<T>> execution) {
    Set<UUID> locked = lockService.lockedAmong(candidateLibraryIds);
    Set<UUID> searchable =
        candidateLibraryIds.stream()
            .filter(id -> !locked.contains(id))
            .collect(Collectors.toUnmodifiableSet());

    ForeignDiagnosticContext context =
        new ForeignDiagnosticContext(
            actor.organizationId(),
            targetKind,
            searchable,
            locked,
            permissionSnapshot(searchable, locked));

    ForeignDiagnosticFindings<T> findings = execution.apply(context);
    List<String> hitRefs = findings.hitRefs() == null ? List.of() : findings.hitRefs();

    DiagnosticContextLogEntry entry =
        logRepository.save(
            new DiagnosticContextLogEntry(
                actor.organizationId(),
                pseudonymService.pseudonymFor(actor.id(), actor.organizationId()).toString(),
                targetKind,
                targetRef,
                question,
                hitRefs.size(),
                joinHitRefs(hitRefs),
                context.permissionSnapshot(),
                justification));
    return new ForeignDiagnosticOutcome<>(context, findings.presentation(), entry.getEventId());
  }

  /** Sorted so the same rights state always renders identically across runs and installations. */
  private static String permissionSnapshot(Set<UUID> searchable, Set<UUID> locked) {
    return "libraries=" + sortedIds(searchable) + ";lockedLibraries=" + sortedIds(locked);
  }

  private static String sortedIds(Set<UUID> ids) {
    return ids.stream()
        .sorted(Comparator.comparing(UUID::toString))
        .map(UUID::toString)
        .collect(Collectors.joining(",", "[", "]"));
  }

  /**
   * Truncated rather than rejected: an oversized hit list must not turn a completed, already
   * displayed diagnosis into a failed one - the entry would then be missing for an execution that
   * happened. The marker makes the truncation visible instead of silent.
   */
  private static String joinHitRefs(List<String> hitRefs) {
    String joined = String.join(",", hitRefs);
    if (joined.length() <= MAX_HIT_REFS_LENGTH) {
      return joined;
    }
    return joined.substring(0, MAX_HIT_REFS_LENGTH) + ",…";
  }

  private static String requireText(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new ValidationException(label + " ist erforderlich");
    }
    String trimmed = value.strip();
    if (trimmed.length() > maxLength) {
      throw new ValidationException(label + " darf höchstens " + maxLength + " Zeichen lang sein");
    }
    return trimmed;
  }
}
