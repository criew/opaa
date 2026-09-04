package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.library.LibraryAccessService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The one entry point for running a search diagnosis in a foreign rights context. Every rule of
 * Leitplanken (c)-(g) and (j) is enforced here, in this order: befugnis, justification,
 * Diagnosesperre, execution, protocol.
 *
 * <p>The callback shape is the reason this is a service and not a set of check methods: a caller
 * cannot obtain a {@link ForeignDiagnosticContext} without also handing back its {@link
 * ForeignDiagnosticFindings}, and the protocol entry is written from those findings before this
 * method returns - including when the callback throws, so a caller cannot keep what it saw by
 * failing after it saw it, and through {@link DiagnosticContextLogWriter}, so a rollback of the
 * calling transaction cannot take the entry with it. "Ein Protokolleintrag je Ausfuehrung" is
 * therefore structural, not a rule a future call site could forget.
 *
 * <p>Not covered here on purpose: a diagnosis in the caller's own rights context. It is not a
 * foreign context, needs no befugnis and produces no protocol entry (Leitplanke (c), last bullet) -
 * a caller running one must not route it through this class.
 */
@Service
public class ForeignDiagnosticContextService {

  private static final Logger log = LoggerFactory.getLogger(ForeignDiagnosticContextService.class);

  private static final int MAX_QUESTION_LENGTH = 2000;
  private static final int MAX_JUSTIFICATION_LENGTH = 1000;
  private static final int MAX_HIT_REFS_LENGTH = 8000;

  private final DiagnosticImpersonationGrantService grantService;
  private final LibraryDiagnosticsLockService lockService;
  private final LibraryAccessService libraryAccessService;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final AuditActorPseudonymService pseudonymService;
  private final DiagnosticContextLogWriter logWriter;

  ForeignDiagnosticContextService(
      DiagnosticImpersonationGrantService grantService,
      LibraryDiagnosticsLockService lockService,
      LibraryAccessService libraryAccessService,
      UserRepository userRepository,
      GroupRepository groupRepository,
      AuditActorPseudonymService pseudonymService,
      DiagnosticContextLogWriter logWriter) {
    this.grantService = grantService;
    this.lockService = lockService;
    this.libraryAccessService = libraryAccessService;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.pseudonymService = pseudonymService;
    this.logWriter = logWriter;
  }

  /**
   * Runs {@code execution} in the requested foreign context and records the execution.
   *
   * @throws ValidationException if a person context carries no free-text justification, if the
   *     caller names themselves as the target, or if the request is otherwise malformed
   * @throws AccessDeniedException if the caller holds no valid "Sicht als" befugnis covering the
   *     target person's Organisationseinheit, or if a profile names a library the caller may not
   *     read themselves
   */
  // Deliberately not @Transactional: every step reads through its own transactional boundary, and
  // the protocol entry is written with Propagation.NOT_SUPPORTED anyway - an ambient transaction
  // would add no atomicity, only a connection held for the whole execution, which since #1150 is a
  // full retrieval run including embedding and rerank calls to external endpoints.
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

  /**
   * A profile is exempt from befugnis and Begründung only because Leitplanke (c) assumes it "zeigt
   * nichts, was die ausführende Person nicht ohnehin sehen darf". For a non-administrative caller
   * that assumption is enforced here rather than assumed: the library set is checked against the
   * executing person's own readable libraries, which are organization-scoped, so neither a foreign
   * nor an organization-foreign library can enter a profile context. A {@code SYSTEM_ADMIN} caller
   * skips that containment check entirely, mirroring {@link LibraryAccessService#effectiveRole}'s
   * administrative fail-open - {@link LibraryAccessService#readableLibraryIds} itself never
   * bypasses (search stays scoped to actually granted libraries), so without this the same
   * administrator {@code SearchDiagnosisService#diagnose} lets run directly would be refused here
   * for any profile touching a library they administer but hold no grant on. Today the only
   * reachable caller is {@code SearchAdminController#runDiagnosis}, which itself requires {@code
   * SYSTEM_ADMIN} - the containment check below is therefore currently unreachable in production
   * and stands as defense in depth for a future, less privileged entry point.
   *
   * <p>The target is the profile's group id, and its library set is resolved from that same group -
   * a caller can neither put a person's name into {@code target_ref} nor record one profile while
   * searching another one's libraries.
   */
  private <T> ForeignDiagnosticOutcome<T> executeForProfile(
      CurrentUser actor,
      ForeignDiagnosticRequest request,
      String question,
      Function<ForeignDiagnosticContext, ForeignDiagnosticFindings<T>> execution) {
    if (request.targetUserId() != null) {
      throw new ValidationException("Ein Rechteprofil trägt keine Zielperson");
    }
    if (request.profileGroupId() == null) {
      throw new ValidationException("Für „Sicht als (Rechteprofil)“ fehlt das Rechteprofil");
    }
    Group profile =
        groupRepository
            .findById(request.profileGroupId())
            .filter(group -> actor.organizationId().equals(group.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Rechteprofil nicht gefunden"));
    Set<UUID> candidates =
        libraryAccessService.readableLibraryIdsForGroup(profile.getId(), actor.organizationId());
    if (!actor.isSystemAdmin()) {
      Set<UUID> ownReadable =
          libraryAccessService.readableLibraryIds(actor.id(), actor.organizationId());
      if (!ownReadable.containsAll(candidates)) {
        throw new AccessDeniedException(
            "Ein Rechteprofil darf nur Bibliotheken umfassen, die Sie selbst einsehen dürfen");
      }
    }
    return run(
        actor,
        DiagnosticTargetKind.PERMISSION_PROFILE,
        profile.getId().toString(),
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

    ForeignDiagnosticFindings<T> findings;
    try {
      findings = execution.apply(context);
    } catch (RuntimeException failed) {
      // The vetted context has already been handed out at this point, so the entry is owed no
      // matter how the execution ended - a caller must not be able to keep what it saw by
      // throwing after it saw it. No hit is recorded because none was reported.
      try {
        logWriter.record(
            entry(actor, targetKind, targetRef, question, List.of(), context, justification));
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the protocol entry for a foreign-context diagnosis whose execution"
                + " threw - the execution is reported correctly, but its entry is missing",
            loggingFailure);
        failed.addSuppressed(loggingFailure);
      }
      throw failed;
    }

    List<String> hitRefs = findings.hitRefs() == null ? List.of() : findings.hitRefs();
    DiagnosticContextLogEntry entry =
        logWriter.record(
            entry(actor, targetKind, targetRef, question, hitRefs, context, justification));
    return new ForeignDiagnosticOutcome<>(context, findings.presentation(), entry.getEventId());
  }

  private DiagnosticContextLogEntry entry(
      CurrentUser actor,
      DiagnosticTargetKind targetKind,
      String targetRef,
      String question,
      List<String> hitRefs,
      ForeignDiagnosticContext context,
      String justification) {
    return new DiagnosticContextLogEntry(
        actor.organizationId(),
        pseudonymService.pseudonymFor(actor.id(), actor.organizationId()).toString(),
        targetKind,
        targetRef,
        question,
        hitRefs.size(),
        joinHitRefs(hitRefs),
        context.permissionSnapshot(),
        justification);
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
   * happened.
   *
   * <p>Truncation cuts between identifiers, never inside one, and appends {@code …(+N)} naming how
   * many were left out. {@code hit_count} therefore stays the number of hits actually displayed and
   * equals the identifiers present plus N - the two fields cannot diverge unnoticed, which a bare
   * truncation marker did not guarantee. {@link #MAX_HIT_REFS_LENGTH} bounds the identifiers; the
   * marker is added on top of it.
   */
  private static String joinHitRefs(List<String> hitRefs) {
    String joined = String.join(",", hitRefs);
    if (joined.length() <= MAX_HIT_REFS_LENGTH) {
      return joined;
    }
    int kept = 0;
    int length = 0;
    for (String ref : hitRefs) {
      int lengthWithRef = kept == 0 ? ref.length() : length + 1 + ref.length();
      if (lengthWithRef > MAX_HIT_REFS_LENGTH) {
        break;
      }
      length = lengthWithRef;
      kept++;
    }
    String truncationMarker = "…(+" + (hitRefs.size() - kept) + ")";
    return kept == 0
        ? truncationMarker
        : String.join(",", hitRefs.subList(0, kept)) + "," + truncationMarker;
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
