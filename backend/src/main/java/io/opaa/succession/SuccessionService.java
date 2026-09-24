package io.opaa.succession;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetType;
import io.opaa.permission.SuccessionCaseCloser;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import io.opaa.permission.SuccessionReachGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operational list and the state at one object (#1819, ADR-0036 Entscheidung 6).
 *
 * <p><b>Derived on every read.</b> The entries come from the {@link SuccessionFindingSource}s, not
 * from the table: an object that has a capable responsible party again disappears from the list at
 * once, without anybody clearing a flag. Only the age and the Sichtungsvermerk come from {@code
 * succession_cases} - and an entry the detection run has not seen yet is listed nonetheless, with
 * no age: the list is complete from day one (Betrieb 6.1).
 *
 * <p><b>Object-bound in both directions</b> (Personalrat E1, Z7): the entry point is the object,
 * the owner is named per line, and there is no query, no sort and no parameter by previous owner or
 * by acting person. The order is fixed - oldest first - so no sort key can become one by the back
 * door.
 */
@Service
public class SuccessionService implements SuccessionReachGuard, SuccessionCaseCloser {

  /** How many entries one tab composes before the answer is refused rather than trimmed. */
  static final int MAX_ENTRIES = 5_000;

  /** The page size bound the specification declares; a request above it is refused, not clamped. */
  static final int MAX_PAGE_SIZE = 200;

  /** The stable {@code code} of the {@code 409} a frozen reach produces. */
  public static final String SUCCESSION_OPEN = "SUCCESSION_OPEN";

  private final List<SuccessionFindingSource> sources;
  private final SuccessionCaseRepository cases;
  private final SuccessionReviewRepository reviews;
  private final SuccessionProperties properties;
  private final Clock clock;

  SuccessionService(
      List<SuccessionFindingSource> sources,
      SuccessionCaseRepository cases,
      SuccessionReviewRepository reviews,
      SuccessionProperties properties,
      Clock clock) {
    this.sources = sources;
    this.cases = cases;
    this.reviews = reviews;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * One tab of the list, oldest first; a page beyond the end is empty, never an error - a page or
   * size outside the declared bounds is refused rather than silently corrected.
   */
  @Transactional(readOnly = true)
  public SuccessionPage list(UUID organizationId, SuccessionKind kind, int page, int size) {
    List<SuccessionFinding> findings = new ArrayList<>();
    for (SuccessionFindingSource source : sources) {
      if (source.kind() == kind) {
        findings.addAll(source.findingsOf(organizationId));
      }
    }
    if (findings.size() > MAX_ENTRIES) {
      throw new ValidationException(
          "Die Liste umfasst mehr als "
              + MAX_ENTRIES
              + " Einträge und wird deshalb nicht ausgegeben. Eine gekürzte Liste sähe vollständig"
              + " aus.");
    }

    Map<CaseKey, SuccessionCase> openCases = new HashMap<>();
    for (SuccessionCase open :
        cases.findByOrganizationIdAndKindAndClosedAtIsNull(organizationId, kind)) {
      openCases.put(new CaseKey(open.getObjectType(), open.getObjectId()), open);
    }
    Map<UUID, SuccessionReview> newestReview = newestReviews(openCases.values());

    Instant now = clock.instant();
    Instant agingCutoff = now.minus(properties.agingThresholdMonths() * 30L, ChronoUnit.DAYS);
    List<SuccessionEntry> entries = new ArrayList<>();
    for (SuccessionFinding finding : findings) {
      SuccessionCase open = openCases.get(new CaseKey(finding.objectType(), finding.objectId()));
      SuccessionReview review = open == null ? null : newestReview.get(open.getId());
      Instant firstSeenAt = open == null ? null : open.getFirstSeenAt();
      boolean highlighted =
          firstSeenAt != null
              && firstSeenAt.isBefore(agingCutoff)
              && (review == null || review.getReviewedAt().isBefore(agingCutoff));
      entries.add(
          new SuccessionEntry(
              open == null ? null : open.getId(),
              finding,
              firstSeenAt,
              highlighted,
              review == null ? null : review.getReviewedAt(),
              review == null ? null : review.getReason()));
    }
    // Oldest first, and an entry the run has not seen yet at the end: it is the youngest there is.
    entries.sort(
        Comparator.comparing(
                SuccessionEntry::firstSeenAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(entry -> entry.finding().objectId().toString()));

    // Refused, never clamped: answering page 0 to a request for page 7, or 200 entries to a
    // request for 500, looks like the answer that was asked for. Same reasoning as the bound above.
    if (page < 0) {
      throw new ValidationException("Die Seitennummer darf nicht negativ sein");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException(
          "Die Seitengröße muss zwischen 1 und " + MAX_PAGE_SIZE + " liegen");
    }
    int fromIndex = Math.min(page * size, entries.size());
    int toIndex = Math.min(fromIndex + size, entries.size());
    return new SuccessionPage(
        entries.subList(fromIndex, toIndex),
        page,
        size,
        entries.size(),
        (int) Math.ceil((double) entries.size() / size));
  }

  /**
   * The state of one object, for its own view and for the reach guards. Empty when the object is in
   * order. Every asset type is answered for as {@link SuccessionObjectType#ASSET}, so no type of
   * the shell escapes the reach guards.
   */
  @Transactional(readOnly = true)
  public Optional<SuccessionFinding> findingFor(SuccessionObjectType objectType, UUID objectId) {
    for (SuccessionFindingSource source : sources) {
      if (source.kind() == SuccessionKind.OPEN_SUCCESSION && source.answersFor(objectType)) {
        Optional<SuccessionFinding> finding = source.findingFor(objectId);
        if (finding.isPresent()) {
          return finding;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * The state of one asset of the asset shell, whatever its type - every type is answered for as
   * {@link SuccessionObjectType#ASSET}.
   */
  @Transactional(readOnly = true)
  public Optional<SuccessionFinding> findingForAsset(AssetType assetType, UUID assetId) {
    return findingFor(SuccessionObjectType.ASSET, assetId)
        .filter(finding -> assetType.equals(finding.assetType()));
  }

  /** Whether this object's succession is open - the question the reach guards ask. */
  @Transactional(readOnly = true)
  public boolean isOpen(SuccessionObjectType objectType, UUID objectId) {
    return findingFor(objectType, objectId).isPresent();
  }

  /**
   * The frozen reach of ADR-0036, Entscheidung 6. The refusal names what holds and who is
   * responsible - "Nichts wird gelöscht" is the other half of the sentence and needs no code: this
   * guard is the only thing the state does.
   */
  @Override
  @Transactional(readOnly = true)
  public void requireReachNotFrozen(
      SuccessionObjectType objectType, UUID objectId, String attemptedAction) {
    SuccessionFinding finding = findingFor(objectType, objectId).orElse(null);
    if (finding == null) {
      return;
    }
    throw new ConflictException(
        "Für dieses Objekt ist die Nachfolge offen: "
            + attemptedAction
            + " ist deshalb nicht möglich. Bestehende Rechte bleiben unverändert, und nichts wird"
            + " gelöscht. Zuständig: "
            + addresseeLabel(finding),
        SUCCESSION_OPEN);
  }

  @Override
  @Transactional(readOnly = true)
  public void requireAssetReachNotFrozen(
      AssetType assetType, UUID assetId, String attemptedAction) {
    requireReachNotFrozen(SuccessionObjectType.ASSET, assetId, attemptedAction);
  }

  /** The German wording of the addressee - the same one the list and the object's view use. */
  public static String addresseeLabel(SuccessionFinding finding) {
    return switch (finding.addressee()) {
      case SPACE_ADMINS -> "die übrigen handlungsfähigen ADMIN-Mitglieder des Space";
      case GROUP_STEWARDS -> "die Verantwortlichen der besitzenden Gruppe";
      case SYSTEM_ADMINISTRATION -> "die Systemverwaltung";
    };
  }

  /**
   * Writes a Sichtungsvermerk: "geprüft am …, weiterhin offen, Grund". It lifts the highlight for
   * one more period and triggers nothing else - no deadline, no escalation, no mail.
   */
  @Transactional
  public SuccessionReview review(UUID caseId, String reason, CurrentUser caller) {
    if (reason == null || reason.isBlank()) {
      throw new ValidationException("Ein Sichtungsvermerk braucht einen Grund");
    }
    if (reason.length() > 1000) {
      throw new ValidationException("Der Grund darf höchstens 1000 Zeichen umfassen");
    }
    SuccessionCase open =
        cases
            .findByIdAndOrganizationId(caseId, caller.organizationId())
            .orElseThrow(() -> new NotFoundException("Vorgang nicht gefunden"));
    if (open.getClosedAt() != null) {
      throw new ValidationException(
          "Dieser Vorgang ist beendet; ein Sichtungsvermerk ist nicht mehr nötig");
    }
    return reviews.save(
        new SuccessionReview(
            open.getId(), open.getOrganizationId(), clock.instant(), caller.id(), reason.trim()));
  }

  /**
   * Ends the open succession of one asset and names who ended it - called by an operation that
   * knows its actor, today the transfer of ownership and responsibility (#1834). The transfer also
   * names a space by its asset type, so the record is found by the object alone - its id is unique
   * across every object type.
   */
  @Override
  @Transactional
  public void closeForAsset(AssetType assetType, UUID assetId, UUID endedByUserId) {
    closeIfEnded(assetId, endedByUserId);
  }

  @Override
  @Transactional
  public void closeForGroup(UUID groupId, UUID endedByUserId) {
    closeIfEnded(groupId, endedByUserId);
  }

  /**
   * Closes the one record of the tab "Offene Nachfolgen" - and only if the state has really ended.
   * A transfer to an owner who cannot act either changes nothing: closing and reopening would set
   * the age of the entry back to zero, and the other tabs are about a different question than the
   * one this operation answered.
   */
  private void closeIfEnded(UUID objectId, UUID endedByUserId) {
    for (SuccessionCase open :
        cases.findByKindAndObjectIdAndClosedAtIsNull(SuccessionKind.OPEN_SUCCESSION, objectId)) {
      if (!isOpen(open.getObjectType(), objectId)) {
        open.close(clock.instant(), endedByUserId);
        cases.save(open);
      }
    }
  }

  private Map<UUID, SuccessionReview> newestReviews(Collection<SuccessionCase> openCases) {
    if (openCases.isEmpty()) {
      return Map.of();
    }
    List<UUID> caseIds = openCases.stream().map(SuccessionCase::getId).toList();
    Map<UUID, SuccessionReview> newest = new HashMap<>();
    for (SuccessionReview review : reviews.findByCaseIdInOrderByReviewedAtDesc(caseIds)) {
      newest.putIfAbsent(review.getCaseId(), review);
    }
    return newest;
  }

  private record CaseKey(SuccessionObjectType objectType, UUID objectId) {}
}
