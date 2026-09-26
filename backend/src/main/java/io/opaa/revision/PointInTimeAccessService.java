package io.opaa.revision;

import io.opaa.api.types.AccessAsOfObjectType;
import io.opaa.api.types.AccessAsOfSource;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.audit.AuditAccessGate;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.permission.AssetGrantHistory;
import io.opaa.permission.GroupMembershipHistory;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.PermissionHistoryRetentionService;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembershipHistory;
import io.opaa.space.SpaceMembershipHistoryService;
import io.opaa.space.SpaceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The Stichtagsauskunft: who reached one named object inside one bounded window (#1822, ADR-0036
 * Entscheidung 8). Exactly one object per query and no person parameter - that, and not a
 * Vollmacht, is what bounds this path (Personalrat Z3); the person entry stays closed until
 * #391/#395. Every call passes {@link AuditAccessGate} and leaves a {@code
 * PERMISSION_HISTORY_ACCESSED} entry against the queried object, the rejected attempt included.
 *
 * <p>It reads the history, never today's rows: a group that has since been deleted still explains
 * an access, and a library deleted last week still gets an answer (ADR-0016). Two gaps are named in
 * the answer instead of being left to the reader - the retention cutoff (#1833) and the sources not
 * yet historised ({@link AccessAsOfResult#sourcesNotCovered()}).
 *
 * <p><b>The bounds limit the work, not only the output</b> (see {@link #MAX_SOURCE_INTERVALS} and
 * {@link #MAX_COMPOSED_ENTRIES}): a page of 50 must not cost the composition of a whole
 * department's memberships, and a request that would is refused rather than trimmed - the same line
 * the rest of the funnel takes.
 */
@Service
public class PointInTimeAccessService {

  /**
   * How many intervals one source may contribute before the request is refused. Bounds the read
   * itself, not the page: the composition below multiplies grants by memberships, so an unbounded
   * source is an unbounded answer regardless of {@code size}.
   */
  static final int MAX_SOURCE_INTERVALS = 2_000;

  /** How large the composed answer may become before the request is refused. */
  static final int MAX_COMPOSED_ENTRIES = 5_000;

  /**
   * The sources ADR-0036, Entscheidung 8 lists that carry no history yet - named in every answer so
   * an empty result is not read as "nobody had access". The system role matters most: {@code
   * LibraryAccessService#effectiveRole} hands a system administrator {@code OWNER} on every library
   * of their organization, and no interval records that.
   */
  private static final List<AccessAsOfSource> SOURCES_NOT_COVERED =
      List.of(
          AccessAsOfSource.SYSTEM_ROLE,
          AccessAsOfSource.OWNERSHIP,
          AccessAsOfSource.CAPABILITY,
          AccessAsOfSource.ACCOUNT_STATE);

  private final AuditAccessGate gate;
  private final AuditEventRecorder eventRecorder;
  private final PermissionHistoryService permissionHistory;
  private final SpaceMembershipHistoryService spaceMembershipHistory;
  private final PermissionHistoryRetentionService retentionService;
  private final GroupSubjectDirectory groupDirectory;
  private final UserRepository userRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final SpaceRepository spaceRepository;

  public PointInTimeAccessService(
      AuditAccessGate gate,
      AuditEventRecorder eventRecorder,
      PermissionHistoryService permissionHistory,
      SpaceMembershipHistoryService spaceMembershipHistory,
      PermissionHistoryRetentionService retentionService,
      GroupSubjectDirectory groupDirectory,
      UserRepository userRepository,
      KnowledgeLibraryRepository libraryRepository,
      SpaceRepository spaceRepository) {
    this.gate = gate;
    this.eventRecorder = eventRecorder;
    this.permissionHistory = permissionHistory;
    this.spaceMembershipHistory = spaceMembershipHistory;
    this.retentionService = retentionService;
    this.groupDirectory = groupDirectory;
    this.userRepository = userRepository;
    this.libraryRepository = libraryRepository;
    this.spaceRepository = spaceRepository;
  }

  /**
   * One page of "who reached this object between {@code from} and {@code to}". The window and the
   * paging are mandatory and bounded; a request beyond the bounds is rejected, never trimmed
   * (Personalrat D4).
   */
  public AccessAsOfResult readersOf(
      UUID organizationId,
      UUID callerId,
      String reason,
      AccessAsOfObjectType objectType,
      UUID objectId,
      Instant from,
      Instant to,
      int page,
      int size) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "access-as-of");
    scope.put("objectType", objectType == null ? null : objectType.name());
    scope.put("objectId", objectId == null ? null : objectId.toString());
    scope.put("from", from == null ? null : from.toString());
    scope.put("to", to == null ? null : to.toString());

    return gate.loggedAccess(
        organizationId,
        callerId,
        reason,
        (outcome, cappedReason) ->
            eventRecorder.recordPermissionHistoryAccess(
                organizationId,
                callerId,
                auditObjectTypeOf(objectType),
                objectId,
                scope,
                outcome,
                cappedReason),
        () -> {
          gate.validateTimeRange(from, to);
          requirePagingBounds(page, size);
          List<AccessAsOfEntry> entries =
              objectType == AccessAsOfObjectType.KNOWLEDGE_LIBRARY
                  ? libraryReaders(organizationId, objectId, from, to)
                  : spaceMembers(organizationId, objectId, from, to);
          return page(organizationId, objectType, objectId, from, to, entries, page, size);
        });
  }

  /**
   * The three ways of the readable-library formula, evaluated backwards: grants naming a person,
   * grants naming a group (resolved through the membership intervals of that same period), and
   * grants to "Alle Konten". All three come from the same table since #1931 (ADR-0037), so both
   * directions of the question read one source; the composition mirrors {@code
   * PermissionHistoryService#readableAssetIdsAsOf}, and it is scoped to {@code organizationId} - a
   * foreign object answers like an unknown one.
   */
  private List<AccessAsOfEntry> libraryReaders(
      UUID organizationId, UUID libraryId, Instant from, Instant to) {
    List<AssetGrantHistory> grants =
        capped(
            permissionHistory.assetGrantIntervalsBetween(
                KnowledgeLibrary.ASSET_TYPE,
                libraryId,
                organizationId,
                from,
                to,
                MAX_SOURCE_INTERVALS + 1),
            "Freigaben");
    Set<UUID> groupIds = new HashSet<>();
    for (AssetGrantHistory grant : grants) {
      if (grant.getSubjectType() == AssetGrantSubjectType.GROUP) {
        groupIds.add(grant.getSubjectGroupId());
      }
    }
    List<GroupMembershipHistory> memberships =
        capped(
            permissionHistory.groupMembershipIntervalsBetween(
                groupIds, organizationId, from, to, MAX_SOURCE_INTERVALS + 1),
            "Gruppenmitgliedschaften");
    Map<UUID, String> groupNames =
        groupIds.isEmpty() ? Map.of() : groupDirectory.namesById(groupIds);

    List<AccessAsOfEntry> entries = new ArrayList<>();
    for (AssetGrantHistory grant : grants) {
      Instant grantFrom = max(grant.getValidFrom(), from);
      Instant grantTo = min(min(grant.getValidTo(), grant.getExpiresAt()), to);
      if (!grantFrom.isBefore(grantTo)) {
        continue;
      }
      // Reaches every account without naming one, which is why it stays one entry instead of a
      // list of people - exactly as the organization-wide release did before #1931.
      if (grant.getSubjectType() == AssetGrantSubjectType.ALL_ACCOUNTS) {
        add(
            entries,
            new AccessAsOfEntry(
                AccessBasis.ORGANIZATION_WIDE,
                null,
                null,
                null,
                null,
                grant.getRole(),
                null,
                grantFrom,
                openEnded(grantTo, to)));
        continue;
      }
      if (grant.getSubjectType() == AssetGrantSubjectType.USER) {
        add(
            entries,
            new AccessAsOfEntry(
                AccessBasis.DIRECT_GRANT,
                grant.getSubjectUserId(),
                null,
                null,
                null,
                grant.getRole(),
                null,
                grantFrom,
                openEnded(grantTo, to)));
        continue;
      }
      for (GroupMembershipHistory membership : memberships) {
        if (!membership.getGroupId().equals(grant.getSubjectGroupId())) {
          continue;
        }
        Instant start = max(membership.getValidFrom(), grantFrom);
        Instant end = min(membership.getValidTo(), grantTo);
        if (!start.isBefore(end)) {
          continue;
        }
        add(
            entries,
            new AccessAsOfEntry(
                AccessBasis.GROUP_GRANT,
                membership.getUserId(),
                null,
                grant.getSubjectGroupId(),
                groupNames.get(grant.getSubjectGroupId()),
                grant.getRole(),
                null,
                start,
                openEnded(end, to)));
      }
    }

    return entries;
  }

  /**
   * The space counterpart: memberships of a person and of a group, the latter resolved likewise.
   */
  private List<AccessAsOfEntry> spaceMembers(
      UUID organizationId, UUID spaceId, Instant from, Instant to) {
    List<SpaceMembershipHistory> memberships =
        capped(
            spaceMembershipHistory.membershipIntervalsBetween(
                spaceId, organizationId, from, to, MAX_SOURCE_INTERVALS + 1),
            "Space-Mitgliedschaften");
    Set<UUID> groupIds = new HashSet<>();
    for (SpaceMembershipHistory membership : memberships) {
      if (membership.getSubjectType() == PermissionSubjectType.GROUP) {
        groupIds.add(membership.getSubjectGroupId());
      }
    }
    List<GroupMembershipHistory> groupMemberships =
        capped(
            permissionHistory.groupMembershipIntervalsBetween(
                groupIds, organizationId, from, to, MAX_SOURCE_INTERVALS + 1),
            "Gruppenmitgliedschaften");
    Map<UUID, String> groupNames =
        groupIds.isEmpty() ? Map.of() : groupDirectory.namesById(groupIds);

    List<AccessAsOfEntry> entries = new ArrayList<>();
    for (SpaceMembershipHistory membership : memberships) {
      Instant start = max(membership.getValidFrom(), from);
      Instant end = min(membership.getValidTo(), to);
      if (!start.isBefore(end)) {
        continue;
      }
      if (membership.getSubjectType() == PermissionSubjectType.USER) {
        add(
            entries,
            new AccessAsOfEntry(
                AccessBasis.DIRECT_MEMBERSHIP,
                membership.getSubjectUserId(),
                null,
                null,
                null,
                null,
                membership.getRole(),
                start,
                openEnded(end, to)));
        continue;
      }
      for (GroupMembershipHistory groupMembership : groupMemberships) {
        if (!groupMembership.getGroupId().equals(membership.getSubjectGroupId())) {
          continue;
        }
        Instant memberFrom = max(groupMembership.getValidFrom(), start);
        Instant memberTo = min(groupMembership.getValidTo(), end);
        if (!memberFrom.isBefore(memberTo)) {
          continue;
        }
        add(
            entries,
            new AccessAsOfEntry(
                AccessBasis.GROUP_MEMBERSHIP,
                groupMembership.getUserId(),
                null,
                membership.getSubjectGroupId(),
                groupNames.get(membership.getSubjectGroupId()),
                null,
                membership.getRole(),
                memberFrom,
                openEnded(memberTo, to)));
      }
    }
    return entries;
  }

  /**
   * Sorts and cuts the composed intervals to one page, and resolves the display names of that page
   * alone - a name lookup for every person of a department is the same unbounded work the caps
   * above prevent. The paging happens here rather than in the query because one page is composed
   * from several tables; the bounds are the audit funnel's own.
   */
  private AccessAsOfResult page(
      UUID organizationId,
      AccessAsOfObjectType objectType,
      UUID objectId,
      Instant from,
      Instant to,
      List<AccessAsOfEntry> entries,
      int page,
      int size) {
    entries.sort(
        Comparator.comparing(AccessAsOfEntry::validFrom)
            .thenComparing(entry -> entry.userId() == null ? "" : entry.userId().toString())
            .thenComparing(entry -> entry.basis().name()));
    int safeSize = Math.min(Math.max(size, 1), AuditAccessGate.MAX_PAGE_SIZE);
    int fromIndex = Math.min(page * safeSize, entries.size());
    int toIndex = Math.min(fromIndex + safeSize, entries.size());
    List<AccessAsOfEntry> pageEntries = named(entries.subList(fromIndex, toIndex));
    Instant cutoff = retentionService.retentionCutoff().orElse(null);
    return new AccessAsOfResult(
        objectType,
        objectId,
        objectName(organizationId, objectType, objectId),
        from,
        to,
        cutoff,
        cutoff != null && from.isBefore(cutoff),
        SOURCES_NOT_COVERED,
        pageEntries,
        page,
        safeSize,
        entries.size(),
        (int) Math.ceil((double) entries.size() / safeSize));
  }

  /** Adds one entry, refusing rather than trimming once the answer would leave its bound. */
  private static void add(List<AccessAsOfEntry> entries, AccessAsOfEntry entry) {
    if (entries.size() >= MAX_COMPOSED_ENTRIES) {
      throw new IllegalArgumentException(
          "Die Auskunft umfasst mehr als "
              + MAX_COMPOSED_ENTRIES
              + " Zugriffszeiträume und wird deshalb nicht erstellt - bitte den Zeitraum enger"
              + " fassen. Eine gekürzte Auskunft wird bewusst nicht ausgegeben, weil sie"
              + " vollständig aussähe");
    }
    entries.add(entry);
  }

  /** Refuses a source that hit its read bound - one interval over the cap is already too many. */
  private static <T> List<T> capped(List<T> intervals, String sourceLabel) {
    if (intervals.size() > MAX_SOURCE_INTERVALS) {
      throw new IllegalArgumentException(
          "Der Zeitraum umfasst mehr als "
              + MAX_SOURCE_INTERVALS
              + " Einträge der Quelle \""
              + sourceLabel
              + "\" - bitte den Zeitraum enger fassen");
    }
    return intervals;
  }

  /** The bounds {@link AuditAccessGate#pageable} applies, for a page this class cuts itself. */
  private void requirePagingBounds(int page, int size) {
    gate.pageable(page, size, org.springframework.data.domain.Sort.unsorted());
  }

  private List<AccessAsOfEntry> named(List<AccessAsOfEntry> pageEntries) {
    Set<UUID> userIds = new HashSet<>();
    pageEntries.forEach(
        entry -> {
          if (entry.userId() != null) {
            userIds.add(entry.userId());
          }
        });
    Map<UUID, String> names = userNames(userIds);
    return pageEntries.stream()
        .map(entry -> entry.withUserName(names.get(entry.userId())))
        .toList();
  }

  /**
   * The object's name today, or null once it is gone - scoped to the caller's own organization, so
   * an id from another organization answers like a deleted object rather than confirming a name.
   */
  private String objectName(UUID organizationId, AccessAsOfObjectType objectType, UUID objectId) {
    return objectType == AccessAsOfObjectType.KNOWLEDGE_LIBRARY
        ? libraryRepository
            .findById(objectId)
            .filter(library -> organizationId.equals(library.getOrganizationId()))
            .map(KnowledgeLibrary::getName)
            .orElse(null)
        : spaceRepository
            .findById(objectId)
            .filter(space -> organizationId.equals(space.getOrganizationId()))
            .map(Space::getName)
            .orElse(null);
  }

  private Map<UUID, String> userNames(Set<UUID> userIds) {
    Map<UUID, String> names = new HashMap<>();
    if (userIds.isEmpty()) {
      return names;
    }
    for (User user : userRepository.findAllById(userIds)) {
      names.put(user.getId(), user.getDisplayName());
    }
    return names;
  }

  /**
   * The object an entry is written against - the queried library or space, not {@code audit_log}:
   * this access reads the Rechtehistorie of that object, and an auditor looking the object up later
   * has to find the retrieval among its events.
   */
  private static AuditObjectType auditObjectTypeOf(AccessAsOfObjectType objectType) {
    return objectType == AccessAsOfObjectType.SPACE
        ? AuditObjectType.SPACE
        : AuditObjectType.KNOWLEDGE_LIBRARY;
  }

  /** {@code null} for an interval still in force at the end of the window, the bound otherwise. */
  private static Instant openEnded(Instant end, Instant to) {
    return end.isBefore(to) ? end : null;
  }

  private static Instant max(Instant left, Instant right) {
    return left == null || left.isBefore(right) ? right : left;
  }

  /** {@code null} stands for "still open", which is later than every bounded end. */
  private static Instant min(Instant left, Instant right) {
    if (left == null) {
      return right;
    }
    return right == null || left.isBefore(right) ? left : right;
  }
}
