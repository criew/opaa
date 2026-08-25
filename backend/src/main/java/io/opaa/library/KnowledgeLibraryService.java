package io.opaa.library;

import io.opaa.api.dto.ScheduleFrequency;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.LibraryScheduleCodec;
import io.opaa.indexing.RssFeedStateRepository;
import io.opaa.indexing.VectorChunkStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manages knowledge libraries - the first asset type (#201, see
 * docs/features/spaces-and-assets.md#assets). Read/write access checks are delegated to {@link
 * LibraryAccessService} (#202), which replaced this class's former coarse {@code canRead}/{@code
 * canManage} - see that class's Javadoc for the full reasoning, in particular why group ownership
 * alone no longer implies management rights.
 *
 * <p>{@link #createLibrary} always grants the creator {@link AssetRole#OWNER} explicitly via an
 * {@link AssetGrant} - the right to delete the library and transfer ownership always sits on a
 * named person, never on group membership alone. For a {@link LibraryOwnerType#GROUP} library the
 * owning group additionally gets {@link AssetRole#MANAGER} (sharing and granting roles to others),
 * <em>not</em> {@code OWNER}: every member automatically holding {@code OWNER} would grow without a
 * human decision point as a directory-synchronised group's membership grows (#237) and could never
 * be downgraded once it became the library's only {@code OWNER} grant (#202 code review round 2).
 * The accepted price is that the personal {@code OWNER} grant is lost when its holder leaves - #240
 * (succession instead of blocking) is what regulates that case, not this class.
 *
 * <p>A third owner kind, {@code SYSTEM}, existed from #201 until #521: exactly one library per
 * organization, seeded {@code PRIVATE} with no grants and reachable only to a system administrator.
 * #521 deleted that library and its content outright (migration {@code
 * 031-delete-system-library.yaml}) rather than keep carrying the special case - see the issue and
 * the deleted {@code LibraryOwnerType.SYSTEM} for the history. Every library now has a real owner,
 * and {@link #createLibrary}/{@link #deleteLibrary} carry no owner-kind-specific exception.
 *
 * <p>An automatically provisioned personal library (the {@code personal} flag, {@code
 * ensurePersonalLibrary}) existed from #201 until #522: every user's first login used to create a
 * "Meine Dokumente" upload library alongside their personal space. #522 removed that automation
 * without a replacement - a user who wants a library now creates one themselves via {@link
 * #createLibrary}, exactly like any other library. Libraries the automation had already created
 * before #522 are unaffected: they keep their existing owner grant and simply become ordinary
 * user-owned libraries, indistinguishable from one a user created by hand.
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeLibraryService {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeLibraryService.class);

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  private final KnowledgeLibraryRepository libraryRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final GroupMembershipResolver membershipResolver;
  private final DocumentRepository documentRepository;
  private final AssetGrantRepository grantRepository;
  private final AssetGrantService grantService;
  private final LibraryAccessService accessService;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;
  private final VectorChunkStore vectorChunkStore;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final IndexingJobRepository indexingJobRepository;
  private final IndexingJobService indexingJobService;
  private final RssFeedStateRepository rssFeedStateRepository;
  private final Clock schedulingClock;
  private final LibraryStorageQuotaService storageQuotaService;
  private final LibraryFolderRepository folderRepository;
  private final ApplicationEventPublisher eventPublisher;

  public KnowledgeLibraryService(
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      GroupMembershipResolver membershipResolver,
      DocumentRepository documentRepository,
      AssetGrantRepository grantRepository,
      AssetGrantService grantService,
      LibraryAccessService accessService,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder,
      VectorChunkStore vectorChunkStore,
      FilesystemPathAllowlist filesystemAllowlist,
      IndexingJobRepository indexingJobRepository,
      IndexingJobService indexingJobService,
      RssFeedStateRepository rssFeedStateRepository,
      Clock schedulingClock,
      LibraryStorageQuotaService storageQuotaService,
      LibraryFolderRepository folderRepository,
      ApplicationEventPublisher eventPublisher) {
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.membershipResolver = membershipResolver;
    this.documentRepository = documentRepository;
    this.grantRepository = grantRepository;
    this.grantService = grantService;
    this.accessService = accessService;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
    this.vectorChunkStore = vectorChunkStore;
    this.filesystemAllowlist = filesystemAllowlist;
    this.indexingJobRepository = indexingJobRepository;
    this.indexingJobService = indexingJobService;
    this.rssFeedStateRepository = rssFeedStateRepository;
    this.schedulingClock = schedulingClock;
    this.storageQuotaService = storageQuotaService;
    this.folderRepository = folderRepository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public LibraryDetail createLibrary(LibraryCreation request, CurrentUser caller) {
    UUID currentUserId = caller.id();
    String normalizedName = validateName(request.name());
    validateDescription(request.description());

    LibraryOwnerType ownerType =
        request.ownerType() != null ? request.ownerType() : LibraryOwnerType.USER;

    LibraryVisibility visibility =
        request.visibility() != null ? request.visibility() : LibraryVisibility.PRIVATE;
    boolean listed = Boolean.TRUE.equals(request.listed());
    SourceConfiguration sourceConfiguration = validateSourceConfiguration(request);

    KnowledgeLibrary library;
    Group ownerGroup = null;
    if (ownerType == LibraryOwnerType.GROUP) {
      if (request.ownerId() == null) {
        throw new ValidationException("ownerId ist erforderlich, wenn ownerType GROUP ist");
      }
      ownerGroup = requireGroupInOrganization(request.ownerId(), caller.organizationId());
      if (!membershipResolver.groupIdsForUser(currentUserId).contains(ownerGroup.getId())) {
        throw new AccessDeniedException(
            "Nur Mitglieder der Gruppe können eine Bibliothek in ihrem Namen anlegen");
      }
      // #441: a dissolved group must not receive the group MANAGER grant below, mirroring
      // AssetGrantService#upsertGrant's own check for the exact same case - reused here rather
      // than duplicated so the two grant-writing paths can never disagree on which groups are
      // grantable.
      grantService.requireGrantableGroup(ownerGroup.getId(), caller.organizationId());
      library =
          KnowledgeLibrary.ownedByGroup(
              caller.organizationId(),
              normalizedName,
              request.description(),
              ownerGroup.getId(),
              visibility,
              listed,
              sourceConfiguration.sourceType(),
              sourceConfiguration.sourcePath(),
              sourceConfiguration.sourceUrl(),
              sourceConfiguration.sourceProxy(),
              sourceConfiguration.sourceCredentials(),
              sourceConfiguration.sourceInsecureSsl());
    } else {
      library =
          KnowledgeLibrary.ownedByUser(
              caller.organizationId(),
              normalizedName,
              request.description(),
              currentUserId,
              visibility,
              listed,
              sourceConfiguration.sourceType(),
              sourceConfiguration.sourcePath(),
              sourceConfiguration.sourceUrl(),
              sourceConfiguration.sourceProxy(),
              sourceConfiguration.sourceCredentials(),
              sourceConfiguration.sourceInsecureSsl());
    }

    KnowledgeLibrary saved = libraryRepository.save(library);
    // #202 code review round 2 (Befund 2): a GROUP-owned library grants the *group* MANAGER, not
    // OWNER, and grants the *creator* (a person) OWNER separately - the round-1 fix (group gets
    // OWNER) went a step too far. Every current and future member of the owning group is
    // automatically OWNER under that rule - able to delete the library and transfer ownership -
    // and grows without a human decision point as a directory-synchronised group's membership
    // grows (#237), which is structurally the same defect #201 had, one level up. It is also not
    // demotable: the round-1 group grant is the library's only OWNER grant, so both
    // requireCallerCanTouchExistingGrant and the last-active-OWNER guard permanently protect it -
    // measured as a 409 on both the downgrade and the revoke path.
    //
    // Splitting the two roles keeps the group's real benefit (a centrally maintained library like
    // the feature spec's leitbeispiel "Rechtsquellen Soziales", owner "Referat 50 * Grundsatz",
    // survives its creator's departure - MANAGER already covers sharing and granting roles to
    // others) while keeping the two highest-stakes rights, delete and ownership transfer, on a
    // named person who can be held accountable for them. The accepted price - that OWNER hangs on
    // a person and is lost when they leave - is exactly the case #240 (succession instead of
    // blocking) exists to regulate: the library does not lock, it goes to "Nachfolge offen",
    // usable and frozen against growing reach until a curator is assigned. No other member of the
    // group inherits rights beyond what the group's MANAGER grant itself carries (see the class
    // Javadoc on why mere membership must never imply management on its own).
    if (ownerGroup != null) {
      AssetGrant groupGrant =
          grantRepository.save(
              AssetGrant.forGroup(
                  saved.getId(),
                  saved.getOrganizationId(),
                  ownerGroup.getId(),
                  AssetRole.MANAGER,
                  null,
                  currentUserId));
      // #392/#892: mirrors AssetGrantService#upsertGrant's own GrantChanged publish - this grant is
      // written directly here, not through that service, but is exactly the same kind of event.
      eventPublisher.publishEvent(
          new GrantChanged(
              saved,
              groupGrant,
              GrantChanged.Cause.GRANTED,
              currentUserId,
              AuditEventType.ASSET_GRANT_GRANTED,
              null,
              Map.of("role", AssetRole.MANAGER.name())));
    }
    AssetGrant ownerGrant =
        grantRepository.save(
            AssetGrant.forUser(
                saved.getId(),
                saved.getOrganizationId(),
                currentUserId,
                AssetRole.OWNER,
                null,
                currentUserId));
    eventPublisher.publishEvent(
        new GrantChanged(
            saved,
            ownerGrant,
            GrantChanged.Cause.GRANTED,
            currentUserId,
            AuditEventType.ASSET_GRANT_GRANTED,
            null,
            Map.of("role", AssetRole.OWNER.name())));
    // #238/#892: the library's initial visibility/listed state is also historised, the third
    // source the readable-library formula depends on besides direct and group grants - one
    // LibraryChanged publish, distinct from the grant events above, covers both the history
    // interval and the LIBRARY_CREATED audit entry ("Anlegen ... von Wissensbibliotheken",
    // docs/features/security-and-compliance.md).
    eventPublisher.publishEvent(
        new LibraryChanged(
            saved,
            LibraryChanged.Cause.CREATED,
            currentUserId,
            AuditEventType.LIBRARY_CREATED,
            null,
            libraryAuditPayload(saved)));
    return toLibraryDetail(saved, AssetRole.OWNER);
  }

  private Map<String, Object> libraryAuditPayload(KnowledgeLibrary library) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", library.getName());
    payload.put("visibility", library.getVisibility().name());
    payload.put("listed", library.isListed());
    // sourceType only, deliberately never sourcePath/sourceUrl/sourceCredentials - the audit log
    // is append-only and never purged the way the library row itself can be (ADR-0018,
    // Entscheidung 4: credentials must appear in no log, and path/url are not "rechtlich
    // erheblich" the way LibraryChanged's changedFields comment already reasons for description).
    payload.put("sourceType", library.getSourceType().name());
    return payload;
  }

  /**
   * Lists every library {@code currentUserId} holds a right on, per {@link
   * LibraryAccessService#readableLibraryIds} - direct grant, grant to one of the caller's groups,
   * or organization-wide visibility. Ownership is included because {@link #createLibrary} always
   * grants the creator {@link AssetRole#OWNER} explicitly (see that method), not because ownership
   * is a fourth access path of its own - deliberately the same formula {@link
   * LibraryAccessService#readableLibraryIds} uses for the permission-aware vector search filter, so
   * the two paths can never disagree on which libraries a user may see (#418, closing the
   * divergence #406 already closed for {@code effectiveRole} vs. {@code readableLibraryIds}).
   *
   * <p>{@code myRole} on each entry comes from {@link
   * LibraryAccessService#effectiveRolesForReadableLibraries}, not {@link
   * LibraryAccessService#effectiveRole} - see that method's Javadoc for why: combining this
   * method's uncached membership with that one's cached, per-library role could leave a listed
   * library with an unresolvable ({@code null}) role against a required response field (#425 code
   * review, finding 1), and calling it once per library would cost one extra query per library on a
   * cold cache (#425 code review, nit 4).
   *
   * <p><b>{@code systemAdmin} is accepted for signature parity with the sibling endpoints ({@link
   * #getLibrary}, {@link #updateLibrary}, {@link #deleteLibrary}) but not used here</b>: unlike
   * those methods, this one never grants or denies access, and - per an explicit decision on #418's
   * scope sentence about "die so erreichten Bibliotheken als solche aus[weisen]" - {@code myRole}
   * deliberately never bypasses to {@link AssetRole#OWNER} for a system admin, even for a library
   * they see only by virtue of administering everything. Sorted by name, then id, for a
   * reproducible order across calls - {@link LibraryAccessService#readableLibraryIds} returns a
   * {@code HashSet}, whose iteration order is not guaranteed to be stable.
   */
  public List<LibrarySummary> listLibraries(CurrentUser caller) {
    UUID currentUserId = caller.id();
    Set<UUID> readableIds =
        accessService.readableLibraryIds(currentUserId, caller.organizationId());
    List<KnowledgeLibrary> libraries =
        libraryRepository.findAllById(readableIds).stream()
            .sorted(
                Comparator.comparing(KnowledgeLibrary::getName)
                    .thenComparing(KnowledgeLibrary::getId))
            .toList();
    Map<UUID, AssetRole> roles =
        accessService.effectiveRolesForReadableLibraries(libraries, currentUserId);
    // #477: one grouped query for the whole page's document counts instead of countByLibraryId
    // once per row - a library with no rows here simply has zero documents.
    Map<UUID, Long> documentCounts =
        documentRepository
            .countByLibraryIdIn(libraries.stream().map(KnowledgeLibrary::getId).toList())
            .stream()
            .collect(
                Collectors.toMap(
                    DocumentRepository.LibraryDocumentCount::getLibraryId,
                    DocumentRepository.LibraryDocumentCount::getDocumentCount));
    Map<UUID, String> ownerNames = resolveOwnerNames(libraries);

    return libraries.stream()
        .map(
            library ->
                new LibrarySummary(
                    library,
                    roles.get(library.getId()),
                    documentCounts.getOrDefault(library.getId(), 0L),
                    ownerNames.get(library.getOwnerId())))
        .toList();
  }

  /**
   * Resolves each library's owner display name in two batched queries (one per owner kind) instead
   * of one lookup per library (#438) - the same pattern {@link AssetGrantService#toViews} already
   * uses for grant subject names. A missing entry (owner deleted) simply leaves {@code ownerName}
   * {@code null} on the response, matching {@link LibrarySummary#ownerName()}'s optional nature.
   *
   * <p>Unlike {@link AssetGrantService#toViews}, a {@code USER} owner with no {@code displayName}
   * resolves to {@code null} here rather than falling back to their email address (PR #601 review,
   * finding 1): that method's audience is limited to a library's own {@code MANAGER}s, but this
   * list reaches every reader of an organization-wide or shared library - potentially the whole
   * organization - so leaking an email address here has a materially larger blast radius. The
   * frontend already falls back to a generic label when {@code ownerName} is absent.
   */
  private Map<UUID, String> resolveOwnerNames(List<KnowledgeLibrary> libraries) {
    Set<UUID> userOwnerIds = new HashSet<>();
    Set<UUID> groupOwnerIds = new HashSet<>();
    for (KnowledgeLibrary library : libraries) {
      if (library.getOwnerType() == LibraryOwnerType.USER) {
        userOwnerIds.add(library.getOwnerId());
      } else {
        groupOwnerIds.add(library.getOwnerId());
      }
    }
    Map<UUID, String> ownerNames = new HashMap<>();
    for (User user : userRepository.findAllById(userOwnerIds)) {
      if (user.getDisplayName() != null) {
        ownerNames.put(user.getId(), user.getDisplayName());
      }
    }
    for (Group group : groupRepository.findAllById(groupOwnerIds)) {
      ownerNames.put(group.getId(), group.getName());
    }
    return ownerNames;
  }

  public LibraryDetail getLibrary(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    AssetRole role =
        accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);
    return toLibraryDetail(library, role);
  }

  @Transactional
  public LibraryDetail updateLibrary(UUID libraryId, LibraryUpdate request, CurrentUser caller) {
    UUID currentUserId = caller.id();
    boolean systemAdmin = caller.isSystemAdmin();
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    accessService.requireRole(library, currentUserId, systemAdmin, AssetRole.MANAGER);
    // ADR-0018: sourceType is chosen once, at creation, and is permanent - a library that started
    // as a directory crawl cannot become an upload container (or vice versa) without mixing
    // Bestand and Loeschsemantik the way the ADR explicitly rules out. request.sourceType() is
    // optional purely so resending the current value (e.g. a naive client that echoes
    // LibraryDetail back) is not itself an error - only an actual change is rejected.
    if (request.sourceType() != null && request.sourceType() != library.getSourceType()) {
      throw new ValidationException(
          "sourceType kann nach dem Anlegen der Bibliothek nicht mehr geändert werden");
    }
    // #476 code review, finding 4: the typed configuration - unlike sourceType itself - can be
    // updated (credential rotation, moving a crawl target) without deleting and recreating the
    // library. Only actually replaced when the request carries at least one configuration field
    // (hasSourceConfigurationFields) - a request that only renames the library (every existing
    // caller, e.g. LibraryManagementPage) must leave a FILESYSTEM/HTTP_DIRECTORY/RSS_FEED
    // library's configuration untouched rather than nulling it out because the fields were simply
    // absent from that unrelated request.
    boolean replacesSourceConfiguration = hasSourceConfigurationFields(request);
    SourceConfiguration sourceConfiguration =
        replacesSourceConfiguration ? validateSourceConfigurationForUpdate(library, request) : null;
    // #485: schedule follows the same replace-as-a-whole rule as the source configuration above -
    // only present when the caller actually intends to change it (LibraryUpdate.schedule), so a
    // request that only renames the library leaves an already-configured schedule untouched.
    boolean replacesSchedule = request.schedule() != null;
    ValidatedSchedule validatedSchedule =
        replacesSchedule ? validateSchedule(request.schedule(), library.getSourceType()) : null;

    String normalizedName = validateName(request.name());
    validateDescription(request.description());
    boolean listed = Boolean.TRUE.equals(request.listed());
    String previousName = library.getName();
    String previousDescription = library.getDescription();
    LibraryVisibility previousVisibility = library.getVisibility();
    boolean previousListed = library.isListed();
    String previousSourcePath = library.getSourcePath();
    String previousSourceUrl = library.getSourceUrl();
    String previousSourceProxy = library.getSourceProxy();
    String previousSourceCredentials = library.getSourceCredentials();
    boolean previousSourceInsecureSsl = library.isSourceInsecureSsl();
    library.updateDetails(normalizedName, request.description(), request.visibility(), listed);
    if (replacesSchedule) {
      library.updateSchedule(validatedSchedule.enabled(), validatedSchedule.cron());
    }
    if (replacesSourceConfiguration) {
      library.updateSourceConfiguration(
          sourceConfiguration.sourcePath(),
          sourceConfiguration.sourceUrl(),
          sourceConfiguration.sourceProxy(),
          sourceConfiguration.sourceCredentials(),
          sourceConfiguration.sourceInsecureSsl());
    }
    KnowledgeLibrary updated = libraryRepository.save(library);
    boolean visibilityOrListedChanged =
        updated.getVisibility() != previousVisibility || updated.isListed() != previousListed;
    // #238/#892: only visibility and listed feed the readable-library formula, so only a change
    // to either of them opens a new interval - a rename alone is not a permission change. One
    // LibraryChanged publish covers both the history interval and the ASSET_VISIBILITY_CHANGED
    // audit entry (#392 code review, nit 4: independent of LIBRARY_CHANGED below - a call that
    // renames the library and widens its visibility in the same request writes both).
    if (visibilityOrListedChanged) {
      eventPublisher.publishEvent(
          new LibraryChanged(
              updated,
              LibraryChanged.Cause.VISIBILITY_CHANGED,
              currentUserId,
              AuditEventType.ASSET_VISIBILITY_CHANGED,
              Map.of("visibility", previousVisibility.name(), "listed", previousListed),
              Map.of("visibility", updated.getVisibility().name(), "listed", updated.isListed())));
    }
    boolean nameChanged = !Objects.equals(previousName, updated.getName());
    boolean descriptionChanged = !Objects.equals(previousDescription, updated.getDescription());
    if (nameChanged || descriptionChanged) {
      // #392 code review, finding 4: before/after stay limited to which fields changed, not the
      // free-text description content itself - the specification limits before/after to what is
      // "rechtlich Erheblich" (role, deadline, visibility), and description is user-entered
      // free text that can carry third-party personal data into an append-only log.
      List<String> changedFields = new ArrayList<>();
      if (nameChanged) {
        changedFields.add("name");
      }
      if (descriptionChanged) {
        changedFields.add("description");
      }
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(updated.getOrganizationId())
              .actor(currentUserId)
              .type(AuditEventType.LIBRARY_CHANGED)
              .object(AuditObjectType.KNOWLEDGE_LIBRARY, updated.getId(), updated.getName())
              .before(Map.of("changedFields", changedFields))
              .after(Map.of("changedFields", changedFields))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    // #545: a pure source-configuration change (e.g. rotating sourceCredentials or moving a
    // FILESYSTEM/HTTP_DIRECTORY/RSS_FEED crawl target) previously left no trace at all - neither
    // LIBRARY_CHANGED (name/description) nor ASSET_VISIBILITY_CHANGED (visibility/listed) fires
    // for it, since the edit dialog (#516) resends name/description/visibility/listed unchanged.
    // Only the set of changed fields is recorded, never their values - sourceCredentials in
    // particular must never appear in the log (ADR-0018, Entscheidung 4), so unlike
    // LIBRARY_CHANGED's before/after this event carries no value at all, not even a redacted one.
    if (replacesSourceConfiguration) {
      List<String> changedSourceFields = new ArrayList<>();
      if (!Objects.equals(previousSourcePath, updated.getSourcePath())) {
        changedSourceFields.add("sourcePath");
      }
      boolean sourceUrlChanged = !Objects.equals(previousSourceUrl, updated.getSourceUrl());
      if (sourceUrlChanged) {
        changedSourceFields.add("sourceUrl");
      }
      // #646, PR #665 review "should" finding 3: fk_rss_feed_state_library's ON DELETE CASCADE
      // (migration 045) only clears a library's rss_feed_state row when the library itself is
      // deleted - a sourceUrl change on an otherwise-surviving RSS_FEED library leaves that row
      // behind under the library's own id. Reconfiguring the library back to the same address
      // later would otherwise find its own stale ETag/Last-Modified again and end that run in a
      // false 304, the same defect #646 fixed for a *different* library reusing an address - just
      // one level down, for the same library reusing its own former address. Deleting the row
      // outright (rather than trying to update it) mirrors 045-clear-rss-feed-state: the next run
      // simply costs one full fetch instead of a conditional GET, never a lost document. A no-op
      // for every sourceType other than RSS_FEED, since no such row exists for them.
      if (sourceUrlChanged) {
        rssFeedStateRepository.deleteByLibraryId(updated.getId());
      }
      if (!Objects.equals(previousSourceProxy, updated.getSourceProxy())) {
        changedSourceFields.add("sourceProxy");
      }
      if (!Objects.equals(previousSourceCredentials, updated.getSourceCredentials())) {
        changedSourceFields.add("sourceCredentials");
      }
      if (previousSourceInsecureSsl != updated.isSourceInsecureSsl()) {
        changedSourceFields.add("sourceInsecureSsl");
      }
      if (!changedSourceFields.isEmpty()) {
        auditEventRecorder.recordUserAction(
            AuditEvent.builder()
                .organizationId(updated.getOrganizationId())
                .actor(currentUserId)
                .type(AuditEventType.LIBRARY_SOURCE_UPDATED)
                .object(AuditObjectType.KNOWLEDGE_LIBRARY, updated.getId(), updated.getName())
                .before(Map.of("changedFields", changedSourceFields))
                .after(Map.of("changedFields", changedSourceFields))
                .outcome(AuditOutcome.SUCCESS)
                .build());
      }
    }
    return toLibraryDetail(
        updated, accessService.effectiveRole(updated, currentUserId, systemAdmin));
  }

  @Transactional
  public void deleteLibrary(UUID libraryId, CurrentUser caller) {
    UUID currentUserId = caller.id();
    boolean systemAdmin = caller.isSystemAdmin();
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    // #202 code review round 3 (Blocker 1): deleting requires OWNER, not MANAGER - AssetRole's
    // Javadoc reserves "delete the asset and transfer ownership" for OWNER alone, and canManage
    // (MANAGER) was the wrong gate here: a group's MANAGER grant (round 2's fix for group-owned
    // libraries) could otherwise delete the whole library, taking every grant on it - including the
    // creator's OWNER grant - down with it via ON DELETE CASCADE, sidestepping the round-1/round-2
    // escalation guards entirely instead of being blocked by them. See the requireRole call below.
    accessService.requireRole(library, currentUserId, systemAdmin, AssetRole.OWNER);
    // #433: deleting a library while an indexing run for it is RUNNING would let the run's
    // documentRepository.save fail against fk_documents_library_organization (RESTRICT) once the
    // library is gone - previously surfacing per document as a failed
    // DataIntegrityViolationException
    // instead of a clean outcome. Rather than have the run cope with a vanished target mid-flight,
    // the maintainer decided (issue comment, 2026-08-20) to prevent the situation at the root:
    // block
    // the delete outright while a run is RUNNING. #501 (stuck RUNNING jobs) is a related but
    // separate
    // concern - this guard becomes more useful once that is fixed, since a stuck RUNNING job could
    // otherwise block deletion indefinitely.
    if (indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
        JobStatus.RUNNING, libraryId, library.getOrganizationId())) {
      throw new ConflictException(
          "Die Bibliothek wird gerade indiziert und kann erst nach Abschluss des Laufs gelöscht"
              + " werden");
    }
    // fk_documents_library_organization is RESTRICT (migration 012): deleting a library that
    // still contains documents would otherwise surface as an unhandled
    // DataIntegrityViolationException -> HTTP 500 with no indication of the actual cause.
    // ADR-0018, Entscheidung 5: the "blocked while non-empty" guard stays in force only for
    // UPLOAD, where documents are individually curated and a single deletion is meaningful. For a
    // lauf-basierte (connector) library, that same single deletion is *wirkungslos* - the next run
    // just re-adds the document, since the exclusion mechanism knowledge-sources.md describes does
    // not exist yet - so blocking the library delete on non-empty would make connector libraries
    // practically undeletable instead. Their deletion takes the whole bestand with it (documents
    // and vector store chunks) rather than being blocked.
    long documentCount = documentRepository.countByLibraryId(libraryId);
    long documentsRemoved = 0;
    if (library.getSourceType() == DocumentSourceType.UPLOAD) {
      if (documentCount > 0) {
        throw new ConflictException(
            "Die Bibliothek enthält noch Dokumente und kann nicht gelöscht werden");
      }
    } else if (documentCount > 0) {
      // Bulk deletion via the library_id filter, not per document (#479): a connector library can
      // hold many documents, and this is the same axis the permission-aware vector search already
      // filters on (see KnowledgeLibraryService's own class Javadoc and QueryService).
      //
      // Rows deleted first, chunks second - deferred to after commit (#636, the deleteLibrary
      // counterpart to #631's deleteDocument fix). The reverse order (chunks deleted eagerly,
      // before the row) left a window: the bulk vectorStore.delete only removes chunks that already
      // exist when it runs. If a concurrently RUNNING indexing job for this same library writes new
      // chunks (FileProcessingService#storeChunks) and its conditional status-transition UPDATE
      // (DocumentRepository#markIndexedFromSource, #632) still finds the row - because this method
      // had not deleted it yet - after this deletion finally removes the row, those freshly-written
      // chunks are never caught by the already-run bulk chunk delete and survive as orphans, still
      // returned by /api/v1/query. Deleting the rows first closes that window: the same document
      // row is now either already gone (the conditional UPDATE sees zero rows and self-cleans its
      // own chunks, exactly the case #632 added) or still locked by this still-open transaction
      // (the UPDATE blocks until this transaction commits, then re-evaluates against the
      // now-deleted
      // row and sees zero rows too) - either way, no UPDATE can succeed against a row this method
      // is
      // in the middle of removing.
      //
      // The chunk delete itself is deferred to after commit, not run eagerly here (#636 review
      // round 2, item 3, mirroring LibraryDocumentService#deleteDocument's own after-commit chunk
      // delete): if the transaction rolled back after an eager vectorStore.delete but before commit
      // - a later step in this method throwing, for instance - the document rows would still be
      // here (rolled back too), but their chunks would already be gone for good, leaving INDEXED
      // rows with no chunks that the next indexing run's checksum-based dedup would then skip as
      // unchanged, permanently unfindable. Running only after a successful commit guarantees the
      // rows are actually gone by the time their chunks are removed too.
      documentsRemoved = documentRepository.deleteByLibraryId(libraryId);
      deleteAfterCommit(
          () -> {
            try {
              vectorChunkStore.deleteByLibraryId(libraryId);
            } catch (RuntimeException e) {
              log.error(
                  "Failed to remove vector store chunks for deleted library {} - orphaned chunks"
                      + " may remain",
                  libraryId,
                  e);
            }
          });
    }

    // #238 code review (#427 nit 3): library_id carries no foreign key on the history tables
    // (deliberately - see PermissionHistoryService's class Javadoc), so the CASCADE delete below
    // (fk_asset_grants_library_organization) never closes these intervals on its own. Without this,
    // a deleted library's still-open grant/visibility intervals kept reporting "currently
    // readable"/"currently visible" for a library that no longer exists. Read the live grants
    // before the delete cascades them away.
    for (AssetGrant grant : grantRepository.findByLibraryId(libraryId)) {
      permissionHistoryService.recordGrantClosedByLibraryDeletion(grant, currentUserId);
    }
    permissionHistoryService.recordVisibilityClosedByLibraryDeletion(library, currentUserId);

    // #392: recorded before the row is gone, same reasoning as the history calls above. For a
    // connector library whose bestand was just taken with it (ADR-0018, Entscheidung 5), the
    // removed document count rides along in this same entry rather than a separate event -
    // AuditEventType has no dedicated "bestand removed" event, and this deletion is one atomic
    // administrative action, not two.
    Map<String, Object> deletionPayload = libraryAuditPayload(library);
    if (documentsRemoved > 0) {
      deletionPayload.put("documentsRemoved", documentsRemoved);
    }
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(currentUserId)
            .type(AuditEventType.LIBRARY_DELETED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.getName())
            .before(deletionPayload)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    libraryRepository.delete(library);
  }

  /**
   * Registers {@code cleanup} to run only once the enclosing transaction has committed - mirrors
   * {@code LibraryDocumentService#deleteAfterCommit}'s reasoning (#636 review round 2, item 3):
   * removing a connector library's vector store chunks before {@link #deleteLibrary}'s own
   * transaction commits would destroy data a later rollback still considers live, leaving {@code
   * INDEXED} document rows with no backing chunks. Falls back to running immediately when no
   * transaction is active.
   */
  private void deleteAfterCommit(Runnable cleanup) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      cleanup.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            cleanup.run();
          }
        });
  }

  /**
   * Lists a library's documents, paged and optionally filtered by a case-insensitive substring of
   * the file name (#517) - available for every {@code sourceType}, not just {@code UPLOAD}, so a
   * connector library's indexed bestand is visible the same way an upload library's is.
   *
   * <p><b>Folder-aware since #821 (Epic #520 Phase 2, ADR-0020).</b> Without {@code q}, the
   * response is scoped to exactly one folder level, chosen by {@code folderId} ({@code null} means
   * the library's root, the same convention {@code documents.folder_id}/{@code
   * library_folders.parent_folder_id} already use): {@link #foldersOf} lists that folder's direct
   * subfolders, {@link #breadcrumbOf} its ancestor chain. With {@code q}, the search stays
   * bibliotheksweit regardless of {@code folderId} - it is not used to filter or scope the search
   * itself (ADR-0020, Entscheidung 4 - no folder-scoped retrieval yet) - {@code folders}/{@code
   * breadcrumb} are both empty, and each hit's own {@code folderId}/{@code folderPath} ({@link
   * #toLibraryDocumentEntry}) show where it lives instead. A given {@code folderId} is still
   * validated even then (#821 review round 1, finding 3): an unknown or foreign one answers 404
   * exactly as it would without {@code q}, so a caller cannot distinguish "this folder does not
   * exist" from "it exists, but I only ever check it while browsing, not while searching" - it
   * would otherwise be the one caller-supplied identifier on this endpoint that silently tolerates
   * a value from another library.
   *
   * <p><b>Backward compatibility (#821 acceptance criteria).</b> A caller that omits {@code
   * folderId} - every client before this task - now lists the library's root rather than its whole
   * bestand across every folder. This is accepted, not a regression to guard against: folders can
   * only exist through the CRUD API #820 added, so no library had any folder before this task
   * shipped, and the root-only response is therefore identical to the old whole-library one until a
   * folder is actually created and something is uploaded into it - seeing that content requires
   * navigating into the folder, which is exactly what {@code folderId} is for (frontend follows in
   * #822).
   */
  public LibraryDocumentPage listDocuments(
      UUID libraryId, CurrentUser caller, String q, UUID folderId, Pageable pageable) {
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);

    // #821 review round 1, finding 3: validated unconditionally, before either branch below - a
    // folderId from another library or one that does not exist answers 404 whether or not q is
    // also set, instead of q silently bypassing the check.
    if (folderId != null) {
      requireFolderInLibrary(libraryId, folderId);
    }

    boolean searching = q != null && !q.isBlank();
    if (searching) {
      Page<Document> page =
          documentRepository.findByLibraryIdAndFileNameContainingIgnoreCase(libraryId, q, pageable);
      Map<UUID, LibraryFolder> foldersById =
          LibraryFolderPaths.loadFoldersById(folderRepository, libraryId);
      return new LibraryDocumentPage(
          page.getContent().stream().map(d -> toLibraryDocumentEntry(d, foldersById)).toList(),
          pageable.getPageNumber(),
          pageable.getPageSize(),
          page.getTotalElements(),
          List.of(),
          List.of(),
          null);
    }

    Page<Document> page =
        folderId == null
            ? documentRepository.findByLibraryIdAndFolderIdIsNull(libraryId, pageable)
            : documentRepository.findByLibraryIdAndFolderId(libraryId, folderId, pageable);
    Map<UUID, LibraryFolder> foldersById =
        LibraryFolderPaths.loadFoldersById(folderRepository, libraryId);

    return new LibraryDocumentPage(
        page.getContent().stream().map(d -> toLibraryDocumentEntry(d, foldersById)).toList(),
        pageable.getPageNumber(),
        pageable.getPageSize(),
        page.getTotalElements(),
        foldersOf(libraryId, folderId),
        breadcrumbOf(folderId, foldersById),
        folderId);
  }

  /**
   * The direct subfolders of {@code folderId} ({@code null} meaning the library's root), each with
   * its own <em>recursive</em> document count - its own documents plus every document in every one
   * of its descendant folders, matching {@code LibraryFolderResponse.documentCount}'s semantics
   * (#821 review round 1, finding 4) so a subfolder row here shows the same number a subsequent
   * delete confirmation for it would. One recursive-CTE query for every subfolder's count ({@link
   * DocumentRepository#countRecursiveByFolderIdIn}), not one {@link
   * DocumentRepository#countByFolderId}/subtree walk per subfolder.
   */
  private List<LibraryFolderChild> foldersOf(UUID libraryId, UUID folderId) {
    List<LibraryFolder> subfolders =
        folderId == null
            ? folderRepository.findByLibraryIdAndParentFolderIdIsNullOrderByNameAsc(libraryId)
            : folderRepository.findByLibraryIdAndParentFolderIdOrderByNameAsc(libraryId, folderId);
    if (subfolders.isEmpty()) {
      return List.of();
    }
    List<UUID> subfolderIds = subfolders.stream().map(LibraryFolder::getId).toList();
    Map<UUID, Long> documentCounts =
        documentRepository.countRecursiveByFolderIdIn(subfolderIds).stream()
            .collect(
                Collectors.toMap(
                    DocumentRepository.FolderDocumentCount::getFolderId,
                    DocumentRepository.FolderDocumentCount::getDocumentCount));
    return subfolders.stream()
        .map(
            folder ->
                new LibraryFolderChild(folder, documentCounts.getOrDefault(folder.getId(), 0L)))
        .toList();
  }

  /**
   * The ancestor chain of {@code folderId}, root-first, ending with {@code folderId} itself - empty
   * for the library's root (#821). Walks {@code foldersById}, an already-loaded map of the whole
   * library's folders, so this costs no further queries beyond the one {@link
   * LibraryFolderPaths#loadFoldersById} already ran for the page's {@code folderPath} values.
   */
  private List<LibraryFolder> breadcrumbOf(UUID folderId, Map<UUID, LibraryFolder> foldersById) {
    if (folderId == null) {
      return List.of();
    }
    Deque<LibraryFolder> chain = new ArrayDeque<>();
    UUID current = folderId;
    while (current != null) {
      LibraryFolder folder = foldersById.get(current);
      if (folder == null) {
        break;
      }
      chain.addFirst(folder);
      current = folder.getParentFolderId();
    }
    return new ArrayList<>(chain);
  }

  /**
   * Validates {@code folderId} references an existing folder in {@code libraryId} - mirrors {@code
   * LibraryFolderService#resolveParent}'s identical cross-library treatment: a folder from another
   * library answers the same 404 as one that does not exist at all.
   */
  private void requireFolderInLibrary(UUID libraryId, UUID folderId) {
    LibraryFolder folder =
        folderRepository
            .findById(folderId)
            .orElseThrow(() -> new NotFoundException("Ordner nicht gefunden"));
    if (!folder.getLibraryId().equals(libraryId)) {
      throw new NotFoundException("Ordner nicht gefunden");
    }
  }

  private String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ValidationException("name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("name darf höchstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  private void validateDescription(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ValidationException(
          "description darf höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen umfassen");
    }
  }

  /**
   * A library's quellentyp is required at creation (ADR-0018) and each type accepts a strictly
   * different, non-overlapping set of the request's configuration fields - the database enforces
   * the same rule at the row level via {@code chk_knowledge_libraries_source_configuration}
   * (migration 027), this is the 400-before-insert half of that same invariant. {@code
   * sourceInsecureSsl} defaults to {@code false} when omitted, mirroring {@code
   * IndexingTriggerRequest}'s equivalent field.
   */
  private SourceConfiguration validateSourceConfiguration(LibraryCreation request) {
    DocumentSourceType sourceType = request.sourceType();
    if (sourceType == null) {
      throw new ValidationException("sourceType ist erforderlich");
    }
    String sourcePath = blankToNull(request.sourcePath());
    String sourceUrl =
        blankToNull(request.sourceUrl() == null ? null : request.sourceUrl().toString());
    String sourceProxy = blankToNull(request.sourceProxy());
    String sourceCredentials = blankToNull(request.sourceCredentials());
    boolean sourceInsecureSsl = Boolean.TRUE.equals(request.sourceInsecureSsl());

    validateConfigurationForType(
        sourceType, sourcePath, sourceUrl, sourceProxy, sourceCredentials, sourceInsecureSsl);
    return new SourceConfiguration(
        sourceType, sourcePath, sourceUrl, sourceProxy, sourceCredentials, sourceInsecureSsl);
  }

  /**
   * Whether {@code request} carries at least one source configuration field - the signal {@link
   * #updateLibrary} uses to decide whether this call intends to touch the configuration at all.
   * {@code sourceType} deliberately does not count here: it is accepted purely for the
   * resend-the-current-value case (see {@link #updateLibrary}'s own Javadoc comment) and carries no
   * configuration-change intent of its own.
   */
  private boolean hasSourceConfigurationFields(LibraryUpdate request) {
    return request.sourcePath() != null
        || request.sourceUrl() != null
        || request.sourceProxy() != null
        || request.sourceCredentials() != null
        || request.sourceInsecureSsl() != null;
  }

  /**
   * Same per-type validation {@link #validateSourceConfiguration} applies at creation, reused for
   * {@link #updateLibrary} (issue #476, review finding 4): passwordrotation or moving a crawl
   * target must not force deleting and recreating the library, so the typed configuration fields
   * stay updatable even though {@code sourceType} itself never is. {@code sourceType} is always the
   * library's own, already-immutable value - never taken from the update request - so a caller
   * cannot use this path to smuggle in a type change.
   *
   * <p>{@code sourceCredentials} falls back to the library's currently stored value when the
   * request omits it <em>and</em> the new {@code sourceUrl} still names the same origin (scheme,
   * host and port) as the currently stored one (issue #516, PR #542 review finding 1): credentials
   * are write-only (never returned by any API response, ADR-0018), so a client editing e.g. only
   * the path portion of {@code sourceUrl} has no value it could resend even if it wanted to, and
   * without this fallback that edit alone would silently wipe an unrelated, previously configured
   * credential. The fallback is deliberately restricted to the same origin: {@link
   * io.opaa.indexing.AutoindexCrawlerService} sends the stored {@code Authorization} header
   * preemptively on the very first request (RFC 7617 does not require a 401 challenge first), so a
   * caller who does not know a configured credential could otherwise redirect it to a host they
   * control simply by changing {@code sourceUrl} and leaving the credentials field blank - turning
   * "must know the credential" into "can exfiltrate the credential". A host change intentionally
   * drops the stored credential instead (matching the pre-fallback behaviour of #476), forcing the
   * caller to re-enter it for the new host. There is deliberately no way to explicitly clear a
   * stored credential while keeping the same origin - blank input is indistinguishable from "leave
   * unchanged" by design.
   */
  private SourceConfiguration validateSourceConfigurationForUpdate(
      KnowledgeLibrary library, LibraryUpdate request) {
    DocumentSourceType sourceType = library.getSourceType();
    String sourcePath = blankToNull(request.sourcePath());
    String sourceUrl =
        blankToNull(request.sourceUrl() == null ? null : request.sourceUrl().toString());
    String sourceProxy = blankToNull(request.sourceProxy());
    String sourceCredentials = blankToNull(request.sourceCredentials());
    if (sourceCredentials == null
        && SourceOriginMatcher.sameOrigin(library.getSourceUrl(), sourceUrl)) {
      sourceCredentials = library.getSourceCredentials();
    }
    boolean sourceInsecureSsl = Boolean.TRUE.equals(request.sourceInsecureSsl());

    validateConfigurationForType(
        sourceType, sourcePath, sourceUrl, sourceProxy, sourceCredentials, sourceInsecureSsl);
    return new SourceConfiguration(
        sourceType, sourcePath, sourceUrl, sourceProxy, sourceCredentials, sourceInsecureSsl);
  }

  /**
   * The type-bound half of {@code chk_knowledge_libraries_source_configuration} (migration 027),
   * enforced here as a 400 before the insert/update ever reaches the database. {@code RSS_FEED}
   * (#474) is deliberately handled like {@code HTTP_DIRECTORY} - both are run-based, URL-fetched
   * source types (ADR-0018/{@code IndexingSourceType}) with the identical configuration shape. The
   * {@code default} branch is a deliberate fallback for a {@link DocumentSourceType} value this
   * method has not been taught yet: without it, a future enum constant would fall through
   * unvalidated, hit the database's CHECK constraint instead, and surface as an unhandled 500 whose
   * Postgres error text includes the failing row (and thus {@code source_credentials}) - exactly
   * what ADR-0018, Entscheidung 4 rules out.
   */
  private void validateConfigurationForType(
      DocumentSourceType sourceType,
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl) {
    switch (sourceType) {
      case UPLOAD -> {
        if (sourcePath != null
            || sourceUrl != null
            || sourceProxy != null
            || sourceCredentials != null
            || sourceInsecureSsl) {
          throw new ValidationException("sourceType UPLOAD erlaubt keine Quellkonfiguration");
        }
      }
      case FILESYSTEM -> {
        if (sourcePath == null) {
          throw new ValidationException(
              "sourcePath ist erforderlich, wenn sourceType FILESYSTEM ist");
        }
        if (!sourcePath.startsWith("/")) {
          throw new ValidationException("sourcePath muss ein absoluter Pfad sein");
        }
        if (sourceUrl != null || sourceProxy != null || sourceCredentials != null) {
          throw new ValidationException(
              "sourceUrl, sourceProxy und sourceCredentials sind für sourceType FILESYSTEM nicht"
                  + " zulässig");
        }
        if (sourceInsecureSsl) {
          throw new ValidationException(
              "sourceInsecureSsl ist für sourceType FILESYSTEM nicht zulässig");
        }
        // #484/ADR-0018 Entscheidung 6: the actual security boundary for FILESYSTEM - anlage-recht
        // alone no longer gates which sourcePath a caller may configure, the operator-controlled
        // allowlist does. An empty allowlist (the default) disables FILESYSTEM entirely rather than
        // defaulting to "everything allowed", so this check fires before - and independent of -
        // whether sourcePath itself is inside it.
        if (!filesystemAllowlist.isConfigured()) {
          throw new ValidationException(
              "sourceType FILESYSTEM ist deaktiviert: der Betrieb hat keine Verzeichnisse für"
                  + " Dateisystem-Bibliotheken freigegeben");
        }
        if (!filesystemAllowlist.isAllowed(sourcePath)) {
          throw new ValidationException(
              "sourcePath liegt außerhalb der vom Betrieb freigegebenen Verzeichnisse. Die"
                  + " freigegebenen Basisverzeichnisse teilt die Systemverwaltung mit.");
        }
      }
      case HTTP_DIRECTORY -> validateUrlBasedConfiguration(sourceType, sourcePath, sourceUrl);
      case RSS_FEED -> validateUrlBasedConfiguration(sourceType, sourcePath, sourceUrl);
      default ->
          throw new ValidationException("sourceType " + sourceType + " wird nicht unterstützt");
    }
  }

  /** Shared by {@code HTTP_DIRECTORY} and {@code RSS_FEED} (#474) - both carry sourceUrl only. */
  private void validateUrlBasedConfiguration(
      DocumentSourceType sourceType, String sourcePath, String sourceUrl) {
    if (sourceUrl == null) {
      throw new ValidationException(
          "sourceUrl ist erforderlich, wenn sourceType " + sourceType + " ist");
    }
    if (sourcePath != null) {
      throw new ValidationException(
          "sourcePath ist für sourceType " + sourceType + " nicht zulässig");
    }
    URI uri;
    try {
      uri = URI.create(sourceUrl);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("sourceUrl ist keine gültige URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new ValidationException("sourceUrl muss mit http:// oder https:// beginnen");
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * Validates {@code request} against the four intervalstufen {@link ScheduleFrequency} allows
   * (#485) and returns the {@code (enabled, cron)} pair {@link KnowledgeLibrary#updateSchedule}
   * takes - {@code cron} built by {@link LibraryScheduleCodec#toCron}. Rejects a schedule on a
   * {@code UPLOAD} library outright (#485, Zuschnitt 21.08.2026: "nur Konnektorbibliotheken"),
   * mirroring the database's own {@code chk_knowledge_libraries_schedule} (migration 054) as a
   * 400-before-insert.
   */
  private ValidatedSchedule validateSchedule(
      LibraryScheduleUpdate request, DocumentSourceType sourceType) {
    ScheduleFrequency frequency = request.frequency();
    if (frequency == null) {
      throw new ValidationException("frequency ist erforderlich");
    }
    if (frequency != ScheduleFrequency.DISABLED && sourceType == DocumentSourceType.UPLOAD) {
      throw new ValidationException(
          "Ein Zeitplan ist nur für Konnektorbibliotheken verfügbar, nicht für UPLOAD");
    }
    Integer hour = request.hour();
    Integer minute = request.minute();
    var weekday = request.weekday();
    switch (frequency) {
      case DISABLED, HOURLY -> {
        if (hour != null || minute != null || weekday != null) {
          throw new ValidationException(
              "hour, minute und weekday sind für frequency " + frequency + " nicht zulässig");
        }
      }
      case DAILY -> {
        if (hour == null || minute == null) {
          throw new ValidationException(
              "hour und minute sind erforderlich, wenn frequency DAILY ist");
        }
        if (weekday != null) {
          throw new ValidationException("weekday ist für frequency DAILY nicht zulässig");
        }
      }
      case WEEKLY -> {
        if (hour == null || minute == null || weekday == null) {
          throw new ValidationException(
              "hour, minute und weekday sind erforderlich, wenn frequency WEEKLY ist");
        }
      }
    }
    if (frequency == ScheduleFrequency.DISABLED) {
      return new ValidatedSchedule(false, null);
    }
    return new ValidatedSchedule(
        true, LibraryScheduleCodec.toCron(frequency, hour, minute, weekday));
  }

  /** The validated {@code (enabled, cron)} pair {@link KnowledgeLibrary#updateSchedule} takes. */
  private record ValidatedSchedule(boolean enabled, String cron) {}

  /** Groups a validated {@link LibraryCreation}'s source fields for the two entity factories. */
  private record SourceConfiguration(
      DocumentSourceType sourceType,
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl) {}

  /**
   * Resolves a group and enforces the organization boundary, treating a group from another
   * organization as not found - mirrors {@code SpaceService#requireUserInOrganization} and {@code
   * GroupService#loadGroup}. Returns 404 rather than 403 so a caller cannot distinguish "no such
   * group" from "group in another organization" - the same lesson #199's review drew for foreign
   * ids in a request body.
   */
  private Group requireGroupInOrganization(UUID groupId, UUID organizationId) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
    if (!group.getOrganizationId().equals(organizationId)) {
      throw new NotFoundException("Gruppe nicht gefunden");
    }
    return group;
  }

  /**
   * Loads a library and enforces the organization boundary, treating a library from another
   * organization as not found - mirrors {@code SpaceService#loadSpace}. Applies to system admins as
   * well; the boundary is not overstepped even to reveal existence.
   */
  private KnowledgeLibrary loadLibrary(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));

    if (!library.getOrganizationId().equals(caller.organizationId())) {
      throw new NotFoundException("Bibliothek nicht gefunden");
    }
    return library;
  }

  private LibraryDetail toLibraryDetail(KnowledgeLibrary library, AssetRole myRole) {
    long documentCount = documentRepository.countByLibraryId(library.getId());
    // #507/#119: sourcePath/sourceUrl/sourceProxy/schedule/storage quota are administration
    // detail - internal server paths, crawl targets and bestand size - gated at MANAGER, not
    // exposed to a mere VIEWER (or even EDITOR) of an organization-wide library.
    // sourceCredentials is deliberately never read here - ADR-0018 makes it a write-only field
    // that appears in no API response, not even for the library's own owner.
    LibraryManagementDetail managementDetail =
        myRole.atLeast(AssetRole.MANAGER)
            ? toManagementDetail(library)
            : LibraryManagementDetail.EMPTY;
    return new LibraryDetail(library, myRole, documentCount, managementDetail);
  }

  private LibraryManagementDetail toManagementDetail(KnowledgeLibrary library) {
    // #485: schedule/lastScheduledRunsFailed stay null for an UPLOAD library, which cannot carry
    // a schedule at all (chk_knowledge_libraries_schedule) - nextRunAt would otherwise leak the
    // same "does an internal crawl target exist" detail #507 already gates.
    LibraryScheduleDetail schedule = null;
    Boolean lastScheduledRunsFailed = null;
    if (library.getSourceType() != DocumentSourceType.UPLOAD) {
      LibraryScheduleCodec.Schedule parsed = LibraryScheduleCodec.parse(library.getScheduleCron());
      Instant nextRunAt =
          LibraryScheduleCodec.nextRunAt(
              library.getScheduleCron(), schedulingClock.instant(), schedulingClock.getZone());
      schedule =
          new LibraryScheduleDetail(
              parsed.frequency(), parsed.hour(), parsed.minute(), parsed.weekday(), nextRunAt);
      lastScheduledRunsFailed =
          indexingJobService.lastScheduledRunsFailed(library.getId(), library.getOrganizationId());
    }
    return new LibraryManagementDetail(
        library.getSourcePath(),
        library.getSourceUrl(),
        library.getSourceProxy(),
        library.isSourceInsecureSsl(),
        // PR #542 review, nit 3: a non-secret yes/no, not the credential itself (ADR-0018) - lets
        // a client phrase an accurate "leave blank to keep the current credential" hint only when
        // one is actually stored.
        library.getSourceCredentials() != null,
        schedule,
        lastScheduledRunsFailed,
        storageQuotaService.quotaBytes(),
        storageQuotaService.usedBytes(library.getId()));
  }

  private LibraryDocumentEntry toLibraryDocumentEntry(
      Document document, Map<UUID, LibraryFolder> foldersById) {
    return new LibraryDocumentEntry(
        document, LibraryFolderPaths.pathOf(document.getFolderId(), foldersById));
  }
}
