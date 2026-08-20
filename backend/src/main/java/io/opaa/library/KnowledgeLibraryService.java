package io.opaa.library;

import io.opaa.api.dto.LibraryDocumentPageResponse;
import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.audit.AuditSubjectKind;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.FilesystemPathAllowlist;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
  private final VectorStore vectorStore;
  private final FilesystemPathAllowlist filesystemAllowlist;

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
      VectorStore vectorStore,
      FilesystemPathAllowlist filesystemAllowlist) {
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
    this.vectorStore = vectorStore;
    this.filesystemAllowlist = filesystemAllowlist;
  }

  @Transactional
  public LibraryResponse createLibrary(LibraryRequest request, UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    String normalizedName = validateName(request.getName());
    validateDescription(request.getDescription());

    LibraryOwnerType ownerType =
        request.getOwnerType() != null ? request.getOwnerType() : LibraryOwnerType.USER;

    LibraryVisibility visibility =
        request.getVisibility() != null ? request.getVisibility() : LibraryVisibility.PRIVATE;
    boolean listed = Boolean.TRUE.equals(request.getListed());
    SourceConfiguration sourceConfiguration = validateSourceConfiguration(request);

    KnowledgeLibrary library;
    Group ownerGroup = null;
    if (ownerType == LibraryOwnerType.GROUP) {
      if (request.getOwnerId() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "ownerId ist erforderlich, wenn ownerType GROUP ist");
      }
      ownerGroup =
          requireGroupInOrganization(request.getOwnerId(), currentUser.getOrganizationId());
      if (!membershipResolver.groupIdsForUser(currentUserId).contains(ownerGroup.getId())) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Nur Mitglieder der Gruppe koennen eine Bibliothek in ihrem Namen anlegen");
      }
      // #441: a dissolved group must not receive the group MANAGER grant below, mirroring
      // AssetGrantService#upsertGrant's own check for the exact same case - reused here rather
      // than duplicated so the two grant-writing paths can never disagree on which groups are
      // grantable.
      grantService.requireGrantableGroup(ownerGroup.getId(), currentUser.getOrganizationId());
      library =
          KnowledgeLibrary.ownedByGroup(
              currentUser.getOrganizationId(),
              normalizedName,
              request.getDescription(),
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
              currentUser.getOrganizationId(),
              normalizedName,
              request.getDescription(),
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
      permissionHistoryService.recordGrantCreated(groupGrant, currentUserId);
      // #392: mirrors AssetGrantService#upsertGrant's own ASSET_GRANT_GRANTED entry - this grant
      // is written directly here, not through that service, but is exactly the same kind of event.
      auditEventRecorder.recordUserActionOnSubject(
          saved.getOrganizationId(),
          currentUserId,
          AuditEventType.ASSET_GRANT_GRANTED,
          AuditObjectType.KNOWLEDGE_LIBRARY,
          saved.getId(),
          saved.getName(),
          AuditSubjectKind.GROUP,
          ownerGroup.getId(),
          null,
          Map.of("role", AssetRole.MANAGER.name()),
          AuditOutcome.SUCCESS,
          null);
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
    permissionHistoryService.recordGrantCreated(ownerGrant, currentUserId);
    auditEventRecorder.recordUserActionOnSubject(
        saved.getOrganizationId(),
        currentUserId,
        AuditEventType.ASSET_GRANT_GRANTED,
        AuditObjectType.KNOWLEDGE_LIBRARY,
        saved.getId(),
        saved.getName(),
        AuditSubjectKind.USER,
        currentUserId,
        null,
        Map.of("role", AssetRole.OWNER.name()),
        AuditOutcome.SUCCESS,
        null);
    // #238: the library's initial visibility/listed state is also historised, the third source
    // the readable-library formula depends on besides direct and group grants.
    permissionHistoryService.recordLibraryCreated(saved, currentUserId);
    // #392: the library-creation event itself, distinct from the grant events above - "Anlegen ...
    // von Wissensbibliotheken" (docs/features/security-and-compliance.md).
    auditEventRecorder.recordUserAction(
        saved.getOrganizationId(),
        currentUserId,
        AuditEventType.LIBRARY_CREATED,
        AuditObjectType.KNOWLEDGE_LIBRARY,
        saved.getId(),
        saved.getName(),
        null,
        libraryAuditPayload(saved),
        AuditOutcome.SUCCESS,
        null);
    return toLibraryResponse(saved, AssetRole.OWNER);
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
  public List<LibraryListResponse> listLibraries(UUID currentUserId, boolean systemAdmin) {
    User currentUser = requireUser(currentUserId);
    Set<UUID> readableIds =
        accessService.readableLibraryIds(currentUserId, currentUser.getOrganizationId());
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

    return libraries.stream()
        .map(
            library ->
                toLibraryListResponse(
                    library,
                    roles.get(library.getId()),
                    documentCounts.getOrDefault(library.getId(), 0L)))
        .toList();
  }

  public LibraryResponse getLibrary(UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!accessService.canRead(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    return toLibraryResponse(
        library, accessService.effectiveRole(library, currentUserId, systemAdmin));
  }

  @Transactional
  public LibraryResponse updateLibrary(
      UUID libraryId, LibraryUpdateRequest request, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!accessService.canManage(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    // ADR-0018: sourceType is chosen once, at creation, and is permanent - a library that started
    // as a directory crawl cannot become an upload container (or vice versa) without mixing
    // Bestand and Loeschsemantik the way the ADR explicitly rules out. request.getSourceType() is
    // optional purely so resending the current value (e.g. a naive client that echoes
    // LibraryResponse back) is not itself an error - only an actual change is rejected.
    if (request.getSourceType() != null && request.getSourceType() != library.getSourceType()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "sourceType kann nach dem Anlegen der Bibliothek nicht mehr geaendert werden");
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

    String normalizedName = validateName(request.getName());
    validateDescription(request.getDescription());
    boolean listed = Boolean.TRUE.equals(request.getListed());
    String previousName = library.getName();
    String previousDescription = library.getDescription();
    LibraryVisibility previousVisibility = library.getVisibility();
    boolean previousListed = library.isListed();
    library.updateDetails(
        normalizedName, request.getDescription(), request.getVisibility(), listed);
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
    // #238: only visibility and listed feed the readable-library formula, so only a change to
    // either of them opens a new interval - a rename alone is not a permission change.
    if (visibilityOrListedChanged) {
      permissionHistoryService.recordVisibilityChanged(updated, currentUserId);
    }
    // #392 code review, nit 4: ASSET_VISIBILITY_CHANGED and LIBRARY_CHANGED are independent events
    // - a call that renames the library and widens its visibility in the same request writes both,
    // instead of the earlier version's else-if silently dropping the rename whenever visibility
    // also changed.
    if (visibilityOrListedChanged) {
      auditEventRecorder.recordUserAction(
          updated.getOrganizationId(),
          currentUserId,
          AuditEventType.ASSET_VISIBILITY_CHANGED,
          AuditObjectType.KNOWLEDGE_LIBRARY,
          updated.getId(),
          updated.getName(),
          Map.of("visibility", previousVisibility.name(), "listed", previousListed),
          Map.of("visibility", updated.getVisibility().name(), "listed", updated.isListed()),
          AuditOutcome.SUCCESS,
          null);
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
          updated.getOrganizationId(),
          currentUserId,
          AuditEventType.LIBRARY_CHANGED,
          AuditObjectType.KNOWLEDGE_LIBRARY,
          updated.getId(),
          updated.getName(),
          Map.of("changedFields", changedFields),
          Map.of("changedFields", changedFields),
          AuditOutcome.SUCCESS,
          null);
    }
    return toLibraryResponse(
        updated, accessService.effectiveRole(updated, currentUserId, systemAdmin));
  }

  @Transactional
  public void deleteLibrary(UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    // #202 code review round 3 (Blocker 1): deleting requires OWNER, not MANAGER - AssetRole's
    // Javadoc reserves "delete the asset and transfer ownership" for OWNER alone, and canManage
    // (MANAGER) was the wrong gate here: a group's MANAGER grant (round 2's fix for group-owned
    // libraries) could otherwise delete the whole library, taking every grant on it - including the
    // creator's OWNER grant - down with it via ON DELETE CASCADE, sidestepping the round-1/round-2
    // escalation guards entirely instead of being blocked by them. See
    // LibraryAccessService#canDelete.
    if (!accessService.canDelete(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
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
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Die Bibliothek enthaelt noch Dokumente und kann nicht geloescht werden");
      }
    } else if (documentCount > 0) {
      // Bulk deletion via the library_id filter, not per document (#479): a connector library can
      // hold many documents, and this is the same axis the permission-aware vector search already
      // filters on (see KnowledgeLibraryService's own class Javadoc and QueryService).
      vectorStore.delete("library_id == '" + libraryId + "'");
      documentsRemoved = documentRepository.deleteByLibraryId(libraryId);
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
        library.getOrganizationId(),
        currentUserId,
        AuditEventType.LIBRARY_DELETED,
        AuditObjectType.KNOWLEDGE_LIBRARY,
        library.getId(),
        library.getName(),
        deletionPayload,
        null,
        AuditOutcome.SUCCESS,
        null);
    libraryRepository.delete(library);
  }

  /**
   * Lists a library's documents, paged and optionally filtered by a case-insensitive substring of
   * the file name (#517) - available for every {@code sourceType}, not just {@code UPLOAD}, so a
   * connector library's indexed bestand is visible the same way an upload library's is.
   */
  public LibraryDocumentPageResponse listDocuments(
      UUID libraryId, UUID currentUserId, boolean systemAdmin, String q, Pageable pageable) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!accessService.canRead(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }

    Page<Document> page =
        (q == null || q.isBlank())
            ? documentRepository.findByLibraryId(libraryId, pageable)
            : documentRepository.findByLibraryIdAndFileNameContainingIgnoreCase(
                libraryId, q, pageable);

    return new LibraryDocumentPageResponse(
        page.getContent().stream().map(this::toLibraryDocumentResponse).toList(),
        pageable.getPageNumber(),
        pageable.getPageSize(),
        page.getTotalElements());
  }

  private String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "name darf hoechstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  private void validateDescription(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "description darf hoechstens " + MAX_DESCRIPTION_LENGTH + " Zeichen umfassen");
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
  private SourceConfiguration validateSourceConfiguration(LibraryRequest request) {
    DocumentSourceType sourceType = request.getSourceType();
    if (sourceType == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceType ist erforderlich");
    }
    String sourcePath = blankToNull(request.getSourcePath());
    String sourceUrl =
        blankToNull(request.getSourceUrl() == null ? null : request.getSourceUrl().toString());
    String sourceProxy = blankToNull(request.getSourceProxy());
    String sourceCredentials = blankToNull(request.getSourceCredentials());
    boolean sourceInsecureSsl = Boolean.TRUE.equals(request.getSourceInsecureSsl());

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
  private boolean hasSourceConfigurationFields(LibraryUpdateRequest request) {
    return request.getSourcePath() != null
        || request.getSourceUrl() != null
        || request.getSourceProxy() != null
        || request.getSourceCredentials() != null
        || request.getSourceInsecureSsl() != null;
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
      KnowledgeLibrary library, LibraryUpdateRequest request) {
    DocumentSourceType sourceType = library.getSourceType();
    String sourcePath = blankToNull(request.getSourcePath());
    String sourceUrl =
        blankToNull(request.getSourceUrl() == null ? null : request.getSourceUrl().toString());
    String sourceProxy = blankToNull(request.getSourceProxy());
    String sourceCredentials = blankToNull(request.getSourceCredentials());
    if (sourceCredentials == null && sameSourceOrigin(library.getSourceUrl(), sourceUrl)) {
      sourceCredentials = library.getSourceCredentials();
    }
    boolean sourceInsecureSsl = Boolean.TRUE.equals(request.getSourceInsecureSsl());

    validateConfigurationForType(
        sourceType, sourcePath, sourceUrl, sourceProxy, sourceCredentials, sourceInsecureSsl);
    return new SourceConfiguration(
        sourceType, sourcePath, sourceUrl, sourceProxy, sourceCredentials, sourceInsecureSsl);
  }

  /**
   * Whether {@code previousUrl} and {@code nextUrl} name the same origin - scheme, host and
   * (explicit or scheme-default) port - the boundary the stored-credentials fallback in {@link
   * #validateSourceConfigurationForUpdate} is restricted to (issue #516, PR #542 review finding 1).
   * Either URL being {@code null} (FILESYSTEM carries no sourceUrl at all, or the request carries
   * no sourceUrl of its own) or unparsable is treated conservatively as "different origin" - the
   * caller then re-requires the credential rather than risking a false positive match.
   */
  private boolean sameSourceOrigin(String previousUrl, String nextUrl) {
    if (previousUrl == null || nextUrl == null) {
      return false;
    }
    try {
      URI previous = URI.create(previousUrl);
      URI next = URI.create(nextUrl);
      return Objects.equals(previous.getScheme(), next.getScheme())
          && Objects.equals(previous.getHost(), next.getHost())
          && defaultedPort(previous) == defaultedPort(next);
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  /** Resolves the scheme's default port (http 80, https 443) when a URI carries no explicit one. */
  private int defaultedPort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "sourceType UPLOAD erlaubt keine Quellkonfiguration");
        }
      }
      case FILESYSTEM -> {
        if (sourcePath == null) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "sourcePath ist erforderlich, wenn sourceType FILESYSTEM ist");
        }
        if (!sourcePath.startsWith("/")) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "sourcePath muss ein absoluter Pfad sein");
        }
        if (sourceUrl != null || sourceProxy != null || sourceCredentials != null) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "sourceUrl, sourceProxy und sourceCredentials sind fuer sourceType FILESYSTEM nicht"
                  + " zulaessig");
        }
        if (sourceInsecureSsl) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "sourceInsecureSsl ist fuer sourceType FILESYSTEM nicht zulaessig");
        }
        // #484/ADR-0018 Entscheidung 6: the actual security boundary for FILESYSTEM - anlage-recht
        // alone no longer gates which sourcePath a caller may configure, the operator-controlled
        // allowlist does. An empty allowlist (the default) disables FILESYSTEM entirely rather than
        // defaulting to "everything allowed", so this check fires before - and independent of -
        // whether sourcePath itself is inside it.
        if (!filesystemAllowlist.isConfigured()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "sourceType FILESYSTEM ist deaktiviert: der Betrieb hat keine Verzeichnisse fuer"
                  + " Dateisystem-Bibliotheken freigegeben");
        }
        if (!filesystemAllowlist.isAllowed(sourcePath)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "sourcePath liegt ausserhalb der vom Betrieb freigegebenen Verzeichnisse. Die"
                  + " freigegebenen Basisverzeichnisse teilt die Systemverwaltung mit.");
        }
      }
      case HTTP_DIRECTORY -> validateUrlBasedConfiguration(sourceType, sourcePath, sourceUrl);
      case RSS_FEED -> validateUrlBasedConfiguration(sourceType, sourcePath, sourceUrl);
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "sourceType " + sourceType + " wird nicht unterstuetzt");
    }
  }

  /** Shared by {@code HTTP_DIRECTORY} and {@code RSS_FEED} (#474) - both carry sourceUrl only. */
  private void validateUrlBasedConfiguration(
      DocumentSourceType sourceType, String sourcePath, String sourceUrl) {
    if (sourceUrl == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "sourceUrl ist erforderlich, wenn sourceType " + sourceType + " ist");
    }
    if (sourcePath != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "sourcePath ist fuer sourceType " + sourceType + " nicht zulaessig");
    }
    URI uri;
    try {
      uri = URI.create(sourceUrl);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl ist keine gueltige URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sourceUrl muss mit http:// oder https:// beginnen");
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Groups a validated {@link LibraryRequest}'s source fields for the two entity factories. */
  private record SourceConfiguration(
      DocumentSourceType sourceType,
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl) {}

  private User requireUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer nicht gefunden"));
  }

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
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden"));
    if (!group.getOrganizationId().equals(organizationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden");
    }
    return group;
  }

  /**
   * Loads a library and enforces the organization boundary, treating a library from another
   * organization as not found - mirrors {@code SpaceService#loadSpace}. Applies to system admins as
   * well; the boundary is not overstepped even to reveal existence.
   */
  private KnowledgeLibrary loadLibrary(UUID libraryId, UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));

    if (!library.getOrganizationId().equals(currentUser.getOrganizationId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden");
    }
    return library;
  }

  private LibraryListResponse toLibraryListResponse(
      KnowledgeLibrary library, AssetRole myRole, long documentCount) {
    return new LibraryListResponse(
            library.getId(),
            library.getName(),
            library.getOwnerType(),
            library.getVisibility(),
            library.isListed(),
            myRole,
            library.getSourceType(),
            documentCount,
            library.getCreatedAt(),
            library.getUpdatedAt())
        .description(library.getDescription());
  }

  private LibraryResponse toLibraryResponse(KnowledgeLibrary library, AssetRole myRole) {
    // sourceCredentials is deliberately never read here - ADR-0018 makes it a write-only field
    // that appears in no API response, not even for the library's own owner.
    return new LibraryResponse(
            library.getId(),
            library.getName(),
            library.getOwnerType(),
            library.getOwnerId(),
            library.getVisibility(),
            library.isListed(),
            myRole,
            library.getSourceType(),
            library.getCreatedAt(),
            library.getUpdatedAt())
        .description(library.getDescription())
        .documentCount(documentRepository.countByLibraryId(library.getId()))
        .sourcePath(library.getSourcePath())
        .sourceUrl(library.getSourceUrl() == null ? null : URI.create(library.getSourceUrl()))
        .sourceProxy(library.getSourceProxy())
        .sourceInsecureSsl(library.isSourceInsecureSsl())
        // PR #542 review, nit 3: a non-secret yes/no, not the credential itself (ADR-0018) - lets
        // a client phrase an accurate "leave blank to keep the current credential" hint only when
        // one is actually stored.
        .sourceCredentialsSet(library.getSourceCredentials() != null);
  }

  private LibraryDocumentResponse toLibraryDocumentResponse(Document document) {
    return LibraryDocumentResponses.from(document);
  }
}
