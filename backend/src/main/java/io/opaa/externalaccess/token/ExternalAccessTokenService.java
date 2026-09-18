package io.opaa.externalaccess.token;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuance, self view and revocation of personal access tokens (ADR-0035, Entscheidung 2). The raw
 * value leaves this class exactly once, in {@link IssuedExternalAccessToken}; what is stored is its
 * HMAC lookup hash under the fourth purpose of {@link LocalAuthKeyService} and the prefix.
 *
 * <p>Two rules that look like validation but are the point of the feature: a library is selectable
 * only if the person may read it <em>and</em> it is released for external access, and the selection
 * has no edit path at all - a change is a new token, so the audit entry of an issuance stays a
 * correct proof of the Reichweite it granted.
 *
 * <p>The audit entries name the token by its id and never by its name: the free-text field is where
 * Vorgangsnummern and device names end up, and the trail excludes free text in these events. The
 * use of a token is deliberately no event at all.
 */
@Service
public class ExternalAccessTokenService {

  private final ExternalAccessTokenRepository tokens;
  private final KnowledgeLibraryRepository libraries;
  private final LibraryAccessService libraryAccess;
  private final ExternalAccessLibraryRelease release;
  private final ExternalAccessSettingsService settings;
  private final LocalAuthKeyService keys;
  private final AuditEventRecorder audit;
  private final Clock clock;
  private final ZoneId zone;

  public ExternalAccessTokenService(
      ExternalAccessTokenRepository tokens,
      KnowledgeLibraryRepository libraries,
      LibraryAccessService libraryAccess,
      ExternalAccessLibraryRelease release,
      ExternalAccessSettingsService settings,
      LocalAuthKeyService keys,
      AuditEventRecorder audit,
      Clock clock) {
    this.tokens = tokens;
    this.libraries = libraries;
    this.libraryAccess = libraryAccess;
    this.release = release;
    this.settings = settings;
    this.keys = keys;
    this.audit = audit;
    this.clock = clock;
    this.zone = ZoneId.systemDefault();
  }

  /**
   * The ceiling an issuance is checked against, from the channel settings (#1717) - read per call,
   * so a lowered ceiling takes effect on the next issuance without anything being invalidated.
   */
  public Duration maxLifetime() {
    return Duration.ofDays(settings.current().values().tokenMaxLifetimeDays());
  }

  /**
   * Issues a token for {@code ownerId}. Every named library must be readable by that person and
   * released; the expiry is mandatory, in the future and at most {@link #maxLifetime()} away.
   *
   * @return the stored row together with the plain value, which exists nowhere else afterwards
   */
  @Transactional
  public IssuedExternalAccessToken issue(
      UUID ownerId, UUID organizationId, String rawName, List<UUID> libraryIds, Instant expiresAt) {
    if (!settings.isEnabled()) {
      throw new AccessDeniedException("Fremdzugänge sind für diese Installation abgeschaltet");
    }
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty()) {
      throw new ValidationException("Ein Zugangstoken braucht einen Namen");
    }
    if (name.length() > 120) {
      throw new ValidationException("Der Name darf höchstens 120 Zeichen lang sein");
    }
    Set<UUID> selected =
        libraryIds == null ? Set.of() : new LinkedHashSet<>(libraryIds.stream().toList());
    if (selected.isEmpty()) {
      throw new ValidationException("Wählen Sie mindestens eine Wissensbibliothek aus");
    }
    Instant now = clock.instant();
    if (expiresAt == null) {
      throw new ValidationException("Ein Zugangstoken braucht ein Ablaufdatum");
    }
    if (!expiresAt.isAfter(now)) {
      throw new ValidationException("Das Ablaufdatum muss in der Zukunft liegen");
    }
    Duration ceiling = maxLifetime();
    if (expiresAt.isAfter(now.plus(ceiling))) {
      throw new ValidationException(
          "Das Ablaufdatum überschreitet die Höchstlaufzeit von " + ceiling.toDays() + " Tagen");
    }
    requireSelectable(ownerId, organizationId, selected);

    String rawValue = ExternalAccessTokenValues.generate();
    ExternalAccessToken saved =
        tokens.save(
            new ExternalAccessToken(
                ownerId,
                name,
                ExternalAccessTokenValues.prefixOf(rawValue),
                keys.lookupHash(Purpose.EXTERNAL_ACCESS_TOKEN_LOOKUP, rawValue),
                now,
                expiresAt,
                selected));
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(ownerId)
            .type(AuditEventType.API_TOKEN_ISSUED)
            .object(AuditObjectType.API_TOKEN, saved.getId(), null)
            .after(
                Map.of(
                    "libraryIds",
                    selected.stream().map(UUID::toString).sorted().toList(),
                    "expiresAt",
                    expiresAt.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return new IssuedExternalAccessToken(saved, rawValue, namesOf(selected));
  }

  /**
   * Exactly the libraries {@link #issue} accepts from {@code ownerId}: readable by that person and
   * released for external access right now, by name. The offering dialogue and the check are the
   * same set on purpose - anything shown here is issuable, anything else is refused.
   *
   * <p>Empty while the channel is closed, because no issuance would be accepted then either.
   */
  @Transactional(readOnly = true)
  public List<EligibleLibrary> eligibleLibraries(UUID ownerId, UUID organizationId) {
    if (!settings.isEnabled()) {
      return List.of();
    }
    Set<UUID> readable = libraryAccess.readableLibraryIds(ownerId, organizationId);
    if (readable.isEmpty()) {
      return List.of();
    }
    return release.releasedLibrariesAmong(readable).stream()
        .map(
            library ->
                new EligibleLibrary(
                    library.getId(),
                    library.getName(),
                    library.getDescription(),
                    library.getExternalAccessExpiresAt()))
        .sorted(Comparator.comparing(EligibleLibrary::name).thenComparing(EligibleLibrary::id))
        .toList();
  }

  /** A library a new token may name, with the end of the release that makes it selectable. */
  public record EligibleLibrary(
      UUID id, String name, String description, Instant releaseExpiresAt) {}

  /** The caller's own tokens, newest first, each with the names of its selected libraries. */
  @Transactional(readOnly = true)
  public List<ExternalAccessTokenView> listOwn(UUID ownerId) {
    List<ExternalAccessToken> own = tokens.findByUserIdOrderByCreatedAtDesc(ownerId);
    Map<UUID, String> names = namesOf(allSelected(own));
    return own.stream().map(token -> new ExternalAccessTokenView(token, names)).toList();
  }

  /** Revokes one of the caller's own tokens; an unknown or foreign id is not found. */
  @Transactional
  public void revokeOwn(UUID ownerId, UUID organizationId, UUID tokenId) {
    ExternalAccessToken token =
        tokens
            .findByIdAndUserId(tokenId, ownerId)
            .orElseThrow(() -> new NotFoundException("Zugangstoken nicht gefunden"));
    if (token.getRevokedAt() != null) {
      return;
    }
    token.revoke(ExternalAccessTokenRevocationReason.OWNER, clock.instant());
    tokens.save(token);
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(ownerId)
            .type(AuditEventType.API_TOKEN_REVOKED)
            .object(AuditObjectType.API_TOKEN, token.getId(), null)
            .after(
                Map.of("reason", ExternalAccessTokenRevocationReason.OWNER.name(), "by", "OWNER"))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * Records the day of use, at most once per calendar day. Not an audit event and not a counter -
   * the single use of a token is deliberately never written to the trail.
   */
  @Transactional
  public void recordUse(UUID tokenId) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
    tokens
        .findById(tokenId)
        .filter(token -> token.getRevokedAt() == null)
        .filter(token -> token.touch(today))
        .ifPresent(tokens::save);
  }

  /** How many live tokens name this library - a number for its owner, never a person. */
  @Transactional(readOnly = true)
  public long countActiveTokensFor(UUID libraryId) {
    return tokens.countActiveContainingLibrary(libraryId, clock.instant());
  }

  private void requireSelectable(UUID ownerId, UUID organizationId, Set<UUID> selected) {
    Set<UUID> readable = libraryAccess.readableLibraryIds(ownerId, organizationId);
    List<UUID> unreadable = selected.stream().filter(id -> !readable.contains(id)).toList();
    if (!unreadable.isEmpty()) {
      // Same wording for "does not exist", "not readable" and "not released": the selection dialog
      // never confirms the existence of a library the person may not see.
      throw new ValidationException(
          "Mindestens eine Bibliothek ist nicht auswählbar - sie ist Ihnen nicht zugänglich oder"
              + " nicht für Fremdzugänge freigegeben");
    }
    Set<UUID> released = release.releasedAmong(selected);
    if (!released.containsAll(selected)) {
      throw new ValidationException(
          "Mindestens eine Bibliothek ist nicht auswählbar - sie ist Ihnen nicht zugänglich oder"
              + " nicht für Fremdzugänge freigegeben");
    }
  }

  Set<UUID> allSelected(List<ExternalAccessToken> rows) {
    return rows.stream()
        .flatMap(token -> token.getSelectedLibraryIds().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  Map<UUID, String> namesOf(Set<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return Map.of();
    }
    return libraries.findAllById(libraryIds).stream()
        .collect(Collectors.toMap(KnowledgeLibrary::getId, KnowledgeLibrary::getName));
  }

  /** A token together with the names of the libraries it selected - the shape both lists need. */
  public record ExternalAccessTokenView(ExternalAccessToken token, Map<UUID, String> libraryNames) {

    /** The selection in a stable order, with the name of every library that still exists. */
    public List<SelectedLibrary> libraries() {
      List<SelectedLibrary> result = new ArrayList<>();
      for (UUID libraryId : token.getSelectedLibraryIds()) {
        result.add(new SelectedLibrary(libraryId, libraryNames.getOrDefault(libraryId, "")));
      }
      result.sort(Comparator.comparing(SelectedLibrary::name).thenComparing(SelectedLibrary::id));
      return result;
    }
  }

  /** One entry of a token's selection, as both lists show it. */
  public record SelectedLibrary(UUID id, String name) {}

  /** A freshly issued token; {@code rawValue} exists here and in the one response, nowhere else. */
  public record IssuedExternalAccessToken(
      ExternalAccessToken token, String rawValue, Map<UUID, String> libraryNames) {

    public ExternalAccessTokenView view() {
      return new ExternalAccessTokenView(token, libraryNames);
    }
  }
}
