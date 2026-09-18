package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets, takes back and reads a library's Freigabe für Fremdzugänge (#1731,
 * docs/features/external-access.md, "Die Freigabe der Bibliothek").
 *
 * <p>Two rules this class exists for. <b>Who may set it:</b> whoever may hand out read access to
 * the library - {@link AssetRole#MANAGER} and above, which includes the system administration
 * through the ordinary administrative floor of {@link LibraryAccessService#requireRole}. A further
 * approval stage above that was decided against on 18.09.2026. <b>That it ends:</b> a release
 * carries a mandatory expiry of at most {@link ExternalAccessProperties#maxReleaseDays}; it stops
 * taking effect on its own ({@link LibraryExternalAccessExpiryService}), and renewing it is a
 * deliberate act. Nothing here consults the installation-wide channel switch (#1717): the release
 * is an attribute of the library, its enforcement is a separate question (#1720/#1721).
 *
 * <p>The write itself goes through {@link KnowledgeLibrary#updateExternalAccess}, which is
 * package-private, and every change publishes {@link LibraryChanged} so the history interval and
 * the audit entry are written side by side - the same double bookkeeping {@code visibility}/{@code
 * listed} have.
 */
@Service
public class LibraryExternalAccessService {

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final UserRepository userRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final ExternalAccessProperties properties;
  private final InstantSource clock;

  @Autowired
  public LibraryExternalAccessService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      UserRepository userRepository,
      ApplicationEventPublisher eventPublisher,
      ExternalAccessProperties properties) {
    this(
        libraryRepository,
        accessService,
        userRepository,
        eventPublisher,
        properties,
        InstantSource.system());
  }

  LibraryExternalAccessService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      UserRepository userRepository,
      ApplicationEventPublisher eventPublisher,
      ExternalAccessProperties properties,
      InstantSource clock) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.userRepository = userRepository;
    this.eventPublisher = eventPublisher;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Releases {@code libraryId} for Fremdzugänge until {@code expiresAt}, or takes an existing
   * release back. A no-op change (taking back what was never released) leaves no history interval
   * and no audit entry - there is nothing that changed to record.
   */
  @Transactional
  public LibraryExternalAccess setExternalAccess(
      CurrentUser actor, UUID libraryId, boolean enabled, Instant expiresAt) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> actor.organizationId().equals(candidate.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    accessService.requireRole(library, actor.id(), actor.isSystemAdmin(), AssetRole.MANAGER);

    ExternalAccessState previousState = library.getExternalAccessState();
    Instant previousExpiresAt = library.getExternalAccessExpiresAt();
    if (!enabled && previousState != ExternalAccessState.ACTIVE) {
      return describe(library);
    }

    Instant now = clock.instant();
    ExternalAccessState newState =
        enabled ? ExternalAccessState.ACTIVE : ExternalAccessState.WITHDRAWN;
    Instant newExpiresAt =
        enabled ? validateExpiry(expiresAt, now) : rejectExpiryOnWithdrawal(expiresAt, library);
    library.updateExternalAccess(newState, newExpiresAt, actor.id(), now);
    KnowledgeLibrary saved = libraryRepository.save(library);

    eventPublisher.publishEvent(
        new LibraryChanged(
            saved,
            LibraryChanged.Cause.EXTERNAL_ACCESS_CHANGED,
            actor.id(),
            auditPayload(previousState, previousExpiresAt),
            auditPayload(newState, newExpiresAt)));
    return describe(saved);
  }

  /**
   * The release of one library, for the library detail response - carrying the state as it takes
   * effect now ({@link KnowledgeLibrary#effectiveExternalAccessState}), not the state the row
   * happens to still hold until the nightly run catches up.
   */
  @Transactional(readOnly = true)
  public LibraryExternalAccess describe(KnowledgeLibrary library) {
    return new LibraryExternalAccess(
        library.getId(),
        library.effectiveExternalAccessState(clock.instant()),
        library.getExternalAccessExpiresAt(),
        library.getExternalAccessSetAt(),
        resolveDisplayName(library.getExternalAccessSetByUserId()),
        tokenCount(library.getId()),
        properties.maxReleaseDays());
  }

  /**
   * Every currently released library of the caller's organization, soonest expiry first
   * (SYSTEM_ADMIN only). Deliberately the released ones alone: the list exists to make the Bestand
   * of releases reviewable, and a list carrying every library that ever had one would bury it. A
   * release whose Befristung has passed is already gone from here, whether or not the nightly run
   * has written that down.
   */
  @Transactional(readOnly = true)
  public List<ExternalAccessLibrary> listReleasedLibraries(CurrentUser actor) {
    if (!actor.isSystemAdmin()) {
      throw new AccessDeniedException(
          "Nur die Systemverwaltung sieht die freigegebenen Bibliotheken");
    }
    Instant now = clock.instant();
    return libraryRepository
        .findByOrganizationIdAndExternalAccessState(
            actor.organizationId(), ExternalAccessState.ACTIVE)
        .stream()
        .filter(library -> library.isExternalAccessActive(now))
        .sorted(
            Comparator.comparing(KnowledgeLibrary::getExternalAccessExpiresAt)
                .thenComparing(KnowledgeLibrary::getId))
        .map(library -> new ExternalAccessLibrary(library, describe(library)))
        .toList();
  }

  /**
   * How many Zugangstokens contain this library. Always {@code 0} until the tokens themselves exist
   * (#1718) - a placeholder in the value, not in the shape: the release view of the responsible
   * person keeps the field it needs for the annual renewal decision from the start.
   */
  private long tokenCount(UUID libraryId) {
    return 0L;
  }

  private Instant validateExpiry(Instant expiresAt, Instant now) {
    if (expiresAt == null) {
      throw new ValidationException("Eine Freigabe für Fremdzugänge braucht ein Ablaufdatum");
    }
    if (!expiresAt.isAfter(now)) {
      throw new ValidationException("Das Ablaufdatum der Freigabe muss in der Zukunft liegen");
    }
    Instant latest = now.plus(Duration.ofDays(properties.maxReleaseDays()));
    if (expiresAt.isAfter(latest)) {
      throw new ValidationException(
          "Eine Freigabe für Fremdzugänge gilt höchstens "
              + properties.maxReleaseDays()
              + " Tage; danach muss sie bewusst erneuert werden");
    }
    return expiresAt;
  }

  /**
   * Taking a release back never carries a date - accepting one would leave a caller believing they
   * had scheduled something. The library keeps the expiry it ran to, which is what the
   * administration's list and the audit entry show.
   */
  private Instant rejectExpiryOnWithdrawal(Instant expiresAt, KnowledgeLibrary library) {
    if (expiresAt != null) {
      throw new ValidationException(
          "Beim Zurücknehmen der Freigabe darf kein Ablaufdatum angegeben werden");
    }
    return library.getExternalAccessExpiresAt();
  }

  private String resolveDisplayName(UUID userId) {
    if (userId == null) {
      return null;
    }
    return userRepository.findById(userId).map(User::getDisplayName).orElse(null);
  }

  /**
   * Direction and deadline, the two facts the specification names as rechtlich erheblich - never a
   * library description, never anyone holding a token.
   */
  private static Map<String, Object> auditPayload(ExternalAccessState state, Instant expiresAt) {
    return expiresAt == null
        ? Map.of("externalAccess", state.name())
        : Map.of("externalAccess", state.name(), "expiresAt", expiresAt.toString());
  }
}
