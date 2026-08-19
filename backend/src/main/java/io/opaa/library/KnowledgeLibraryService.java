package io.opaa.library;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
 * <p>{@link LibraryOwnerType#SYSTEM} libraries (exactly one per organization, see {@link
 * KnowledgeLibrary#SYSTEM_LIBRARY_ID}) are fail-closed by their seeded state, not by a special case
 * in the rights model (#406): {@code PRIVATE} with no grants excludes everyone under the ordinary
 * formula, and {@link #createLibrary} rejects a caller-supplied {@code SYSTEM} owner type outright
 * - only the migration (012-seed-system-library) ever creates one. {@link #deleteLibrary} still
 * refuses to remove it.
 *
 * <p>What that state does <b>not</b> do any more is make the library permanently unreachable. Its
 * whole content is what the indexing pipeline writes ({@code FileProcessingService}), so a rule
 * that no grant and no visibility could ever open it did not protect a migrated remnant - it made
 * every indexed document unfindable for everyone, system admins included, since the search reads
 * with the asking user's own rights and never bypasses them. Widening it requires {@code MANAGER},
 * which on a library with no owner and no grants only a system admin holds; the decision therefore
 * stays where #201 put it, but it can now actually be taken.
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeLibraryService {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;
  private static final String PERSONAL_LIBRARY_NAME = "Meine Dokumente";

  private final KnowledgeLibraryRepository libraryRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final GroupMembershipResolver membershipResolver;
  private final DocumentRepository documentRepository;
  private final AssetGrantRepository grantRepository;
  private final LibraryAccessService accessService;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;
  private final TransactionTemplate requiresNewTransactionTemplate;

  public KnowledgeLibraryService(
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      GroupMembershipResolver membershipResolver,
      DocumentRepository documentRepository,
      AssetGrantRepository grantRepository,
      LibraryAccessService accessService,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder,
      PlatformTransactionManager transactionManager) {
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.membershipResolver = membershipResolver;
    this.documentRepository = documentRepository;
    this.grantRepository = grantRepository;
    this.accessService = accessService;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional
  public LibraryResponse createLibrary(LibraryRequest request, UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    String normalizedName = validateName(request.getName());
    validateDescription(request.getDescription());

    LibraryOwnerType ownerType =
        request.getOwnerType() != null ? request.getOwnerType() : LibraryOwnerType.USER;
    if (ownerType == LibraryOwnerType.SYSTEM) {
      // Only the migration (012-seed-system-library) creates a SYSTEM-owned library; accepting it
      // here would let any caller mint a second "readable by system admins only" library outside
      // that fail-closed, single-row invariant.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "ownerType SYSTEM kann nicht ueber die API angelegt werden");
    }

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
              false,
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
    // Mirrors the delete guard on the personal library (code review of #201/#305): once #202 makes
    // library_id the filter axis for the permission-aware vector search, widening a personal
    // library's visibility to ORGANIZATION would expose its owner's private documents
    // organization-wide - a change no owner is likely to intend for a library the system, not they,
    // created. The personal library's name and description can still be changed.
    if (library.isPersonal() && request.getVisibility() == LibraryVisibility.ORGANIZATION) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Die Sichtbarkeit der persoenlichen Bibliothek kann nicht auf ORGANIZATION gesetzt"
              + " werden");
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
        replacesSourceConfiguration
            ? validateSourceConfigurationForUpdate(library.getSourceType(), request)
            : null;

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
    if (library.isSystemLibrary()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Die System-Bibliothek kann nicht geloescht werden");
    }
    if (library.isPersonal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Die persoenliche Bibliothek kann nicht geloescht werden");
    }
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
    // DataIntegrityViolationException
    // -> HTTP 500 with no indication of the actual cause. Checking first turns that into a clean,
    // actionable 409.
    if (documentRepository.countByLibraryId(libraryId) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Die Bibliothek enthaelt noch Dokumente und kann nicht geloescht werden");
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

    // #392: recorded before the row is gone, same reasoning as the history calls above.
    auditEventRecorder.recordUserAction(
        library.getOrganizationId(),
        currentUserId,
        AuditEventType.LIBRARY_DELETED,
        AuditObjectType.KNOWLEDGE_LIBRARY,
        library.getId(),
        library.getName(),
        libraryAuditPayload(library),
        null,
        AuditOutcome.SUCCESS,
        null);
    libraryRepository.delete(library);
  }

  public List<LibraryDocumentResponse> listDocuments(
      UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!accessService.canRead(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }

    return documentRepository.findByLibraryId(libraryId).stream()
        .map(this::toLibraryDocumentResponse)
        .toList();
  }

  /**
   * Creates the automatic personal library "Meine Dokumente" for a user if it does not exist yet.
   * Mirrors {@code SpaceService#ensureDefaultSpace} exactly, including its {@code ON CONFLICT ...
   * DO NOTHING} race handling via the partial unique index {@code
   * uk_knowledge_libraries_personal_owner} (migration 012) - see that method's Javadoc for the full
   * reasoning, not repeated here. Both are called from the same {@code UserService} post-commit
   * callback for the same reason: the referenced {@code users} row must already be committed and
   * visible on this method's own connection (see {@code
   * UserService#ensurePersonalAssetsAfterCommit} for why the call is deferred to after commit).
   *
   * <p>Called independently of (not nested inside) {@code SpaceService#ensureDefaultSpace}'s own
   * transaction, so a failure creating the library never rolls back an already-committed personal
   * space and vice versa - each keeps the same self-contained failure boundary #265 established for
   * the personal space alone. "Atomically" in #201's acceptance criteria is satisfied at the level
   * that matters operationally: both calls are always attempted together, from the same afterCommit
   * callback, so provisioning never silently creates one without the other.
   *
   * <p><b>{@code Propagation.NOT_SUPPORTED}, deliberately overriding the class-level
   * {@code @Transactional(readOnly = true)}:</b> without this override, calling this public method
   * through the Spring proxy would open an ambient read-only transaction (and thus hold one JDBC
   * connection) for this method's entire duration, while {@code requiresNewTransactionTemplate}
   * below opens a <em>second</em>, independent connection for its {@code REQUIRES_NEW} transaction
   * - two connections held by one caller at once, the same class of bug #299 fixed in {@code
   * UserService.findOrCreateUser}. {@code SpaceService#ensureDefaultSpace} had the identical defect
   * and is fixed the same way, in this same PR (#201/#305 code review) - not deferred to a
   * follow-up issue, because the fix is one annotation and both methods are exercised together by
   * {@link io.opaa.auth.UserServiceCreationRaceIntegrationTest}. {@code NOT_SUPPORTED} suspends any
   * ambient transaction for this method's duration (there normally is none, since {@code
   * findOrCreateUser} itself is not {@code @Transactional} either) and leaves only the one
   * connection {@code requiresNewTransactionTemplate} actually needs.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void ensurePersonalLibrary(UUID userId, UUID organizationId) {
    if (libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)) {
      return;
    }

    requiresNewTransactionTemplate.executeWithoutResult(
        status -> {
          UUID libraryId = UUID.randomUUID();
          // #238 code review, finding 2: the return value is the number of rows this call itself
          // inserted (0 on the ON CONFLICT ... DO NOTHING no-op path, 1 otherwise) - history is
          // written only on the path that actually inserted, never for a personal library another
          // concurrent call already created, so two racing callers never both write a conflicting
          // open interval for the same library.
          int libraryInserted =
              libraryRepository.insertPersonalLibraryIfAbsent(
                  libraryId,
                  organizationId,
                  PERSONAL_LIBRARY_NAME,
                  "Private persoenliche Wissensbibliothek",
                  userId);
          if (libraryInserted > 0) {
            // Same connection/transaction as the insert above, so this always sees the row it just
            // wrote.
            permissionHistoryService.recordLibraryCreated(
                libraryRepository.findById(libraryId).orElseThrow(), userId);
          }

          UUID grantId = UUID.randomUUID();
          // Same connection/transaction as the insert above, so it always sees the row it just
          // wrote (or the pre-existing one another concurrent call won the race for) - see
          // AssetGrantRepository#insertOwnerGrantForPersonalLibraryIfAbsent.
          int grantInserted =
              grantRepository.insertOwnerGrantForPersonalLibraryIfAbsent(grantId, userId);
          if (grantInserted > 0) {
            permissionHistoryService.recordGrantCreated(
                grantRepository.findById(grantId).orElseThrow(), userId);
          }
        });
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
   */
  private SourceConfiguration validateSourceConfigurationForUpdate(
      DocumentSourceType sourceType, LibraryUpdateRequest request) {
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
            library.isPersonal(),
            myRole,
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
            library.getVisibility(),
            library.isListed(),
            library.isPersonal(),
            myRole,
            library.getSourceType(),
            library.getCreatedAt(),
            library.getUpdatedAt())
        .description(library.getDescription())
        .ownerId(library.getOwnerId())
        .documentCount(documentRepository.countByLibraryId(library.getId()))
        .sourcePath(library.getSourcePath())
        .sourceUrl(library.getSourceUrl() == null ? null : URI.create(library.getSourceUrl()))
        .sourceProxy(library.getSourceProxy())
        .sourceInsecureSsl(library.isSourceInsecureSsl());
  }

  private LibraryDocumentResponse toLibraryDocumentResponse(Document document) {
    return LibraryDocumentResponses.from(document);
  }
}
