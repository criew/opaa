package io.opaa.library;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.Capability;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.ScheduleFrequency;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetOwnerNames;
import io.opaa.asset.AssetShellService;
import io.opaa.asset.AssetSuccessionSource;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.LibraryScheduleCodec;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.source.PushIntake;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettingField;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.LibraryFolder;
import io.opaa.knowledge.LibraryFolderRepository;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.permission.AssetReach;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.SuccessionFinding;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * {@link io.opaa.permission.AssetGrant} - the right to delete the library and transfer ownership
 * always sits on a named person, never on group membership alone. For a {@link
 * AssetOwnerType#GROUP} library the owning group additionally gets {@link AssetRole#MANAGER}
 * (sharing and granting roles to others), <em>not</em> {@code OWNER}: every member automatically
 * holding {@code OWNER} would grow without a human decision point as a directory-synchronised
 * group's membership grows (#237) and could never be downgraded once it became the library's only
 * {@code OWNER} grant (#202 code review round 2). The accepted price is that the personal {@code
 * OWNER} grant is lost when its holder leaves - #240 (succession instead of blocking) is what
 * regulates that case, not this class.
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

  /** 32 random bytes, Base64url without padding: 43 characters (#1140). */
  private static final int WEBHOOK_SECRET_BYTES = 32;

  private final SecureRandom secureRandom = new SecureRandom();

  private static final Logger log = LoggerFactory.getLogger(KnowledgeLibraryService.class);

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  private final KnowledgeLibraryRepository libraryRepository;
  private final AssetOwnerNames assetOwnerNames;
  private final CapabilityService capabilityService;
  private final DocumentRepository documentRepository;
  private final AssetGrantService grantService;
  private final AssetShellService shellService;
  private final LibraryAccessService accessService;
  private final AuditEventRecorder auditEventRecorder;
  private final VectorChunkStore vectorChunkStore;
  private final IndexingJobRepository indexingJobRepository;
  private final IndexingJobService indexingJobService;
  private final Clock schedulingClock;
  private final LibraryStorageQuotaService storageQuotaService;
  private final LibraryExternalAccessService externalAccessService;
  private final LibraryFolderRepository folderRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final SourceConnectorRegistry connectors;
  private final AssetSuccessionSource successionSource;

  public KnowledgeLibraryService(
      AssetSuccessionSource successionSource,
      KnowledgeLibraryRepository libraryRepository,
      AssetOwnerNames assetOwnerNames,
      CapabilityService capabilityService,
      DocumentRepository documentRepository,
      AssetGrantService grantService,
      AssetShellService shellService,
      LibraryAccessService accessService,
      AuditEventRecorder auditEventRecorder,
      VectorChunkStore vectorChunkStore,
      IndexingJobRepository indexingJobRepository,
      IndexingJobService indexingJobService,
      Clock schedulingClock,
      LibraryStorageQuotaService storageQuotaService,
      LibraryExternalAccessService externalAccessService,
      LibraryFolderRepository folderRepository,
      ApplicationEventPublisher eventPublisher,
      SourceConnectorRegistry connectors) {
    this.successionSource = successionSource;
    this.libraryRepository = libraryRepository;
    this.assetOwnerNames = assetOwnerNames;
    this.capabilityService = capabilityService;
    this.documentRepository = documentRepository;
    this.grantService = grantService;
    this.shellService = shellService;
    this.accessService = accessService;
    this.auditEventRecorder = auditEventRecorder;
    this.vectorChunkStore = vectorChunkStore;
    this.indexingJobRepository = indexingJobRepository;
    this.indexingJobService = indexingJobService;
    this.schedulingClock = schedulingClock;
    this.storageQuotaService = storageQuotaService;
    this.externalAccessService = externalAccessService;
    this.folderRepository = folderRepository;
    this.eventPublisher = eventPublisher;
    this.connectors = connectors;
  }

  /**
   * Which capability a library of this source type needs (ADR-0036, Entscheidung 5). A library with
   * an indexing run is its own capability because it reaches server paths and stored credentials; a
   * missing source type - rejected by {@code validateSourceConfiguration} inside {@link
   * #createLibrary} - takes the upload capability, so an unreadable request never decides which
   * right is checked.
   */
  private Capability capabilityFor(DocumentSourceType sourceType) {
    return sourceType == null || !connectors.descriptor(sourceType).indexingRun()
        ? Capability.CREATE_LIBRARY
        : Capability.CREATE_CONNECTOR_LIBRARY;
  }

  @Transactional
  public LibraryDetail createLibrary(LibraryCreation request, CurrentUser caller) {
    capabilityService.requireCapability(caller, capabilityFor(request.sourceType()));
    UUID currentUserId = caller.id();
    String normalizedName = validateName(request.name());
    validateDescription(request.description());

    AssetOwnerType ownerType =
        request.ownerType() != null ? request.ownerType() : AssetOwnerType.USER;

    boolean listed = Boolean.TRUE.equals(request.listed());
    SourceConfiguration sourceConfiguration = validateSourceConfiguration(request);

    KnowledgeLibrary library;
    if (ownerType == AssetOwnerType.GROUP) {
      if (request.ownerId() == null) {
        throw new ValidationException("ownerId ist erforderlich, wenn ownerType GROUP ist");
      }
      // The shell's rule for every asset type: a group of the caller's organization, the caller
      // among its members, and a group that may still receive the owning group's MANAGER grant.
      grantService.requireOwnableGroup(request.ownerId(), KnowledgeLibrary.ASSET_TYPE, caller);
      library =
          KnowledgeLibrary.ownedByGroup(
              caller.organizationId(),
              normalizedName,
              request.description(),
              request.ownerId(),
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
              listed,
              sourceConfiguration.sourceType(),
              sourceConfiguration.sourcePath(),
              sourceConfiguration.sourceUrl(),
              sourceConfiguration.sourceProxy(),
              sourceConfiguration.sourceCredentials(),
              sourceConfiguration.sourceInsecureSsl());
    }
    connectors
        .connector(sourceConfiguration.sourceType())
        .configureNew(library, sourceConfiguration.settings());
    // #1942: the rhythm is set with the library, not in a second call right after it - same
    // validation as on an update, so an UPLOAD library is refused here too instead of by the
    // database's own chk_knowledge_libraries_schedule.
    if (request.schedule() != null) {
      ValidatedSchedule schedule =
          validateSchedule(request.schedule(), sourceConfiguration.sourceType());
      library.updateSchedule(schedule.enabled(), schedule.cron());
    }

    KnowledgeLibrary saved = libraryRepository.save(library);
    // The creator holds OWNER, an owning group MANAGER - never OWNER, see AssetShellService. The
    // shell also opens the ownership and reach intervals and writes LIBRARY_CREATED.
    shellService.registerCreated(saved, currentUserId, libraryAuditPayload(saved));
    return toLibraryDetail(saved, AssetRole.OWNER, currentUserId);
  }

  private Map<String, Object> libraryAuditPayload(KnowledgeLibrary library) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", library.getName());
    payload.put("listed", library.isListed());
    // sourceType only, deliberately never sourcePath/sourceUrl/sourceCredentials - the audit log
    // is append-only and never purged the way the library row itself can be (ADR-0018,
    // Entscheidung 4: credentials must appear in no log, and path/url are not "rechtlich
    // erheblich" - the same reasoning updateLibrary applies to the description).
    payload.put("sourceType", library.getSourceType().name());
    return payload;
  }

  /**
   * Lists every library {@code currentUserId} holds a right on, per {@link
   * LibraryAccessService#readableLibraryIds} - direct grant, grant to one of the caller's groups,
   * or a grant to "Alle Konten". Ownership is included because {@link #createLibrary} always grants
   * the creator {@link AssetRole#OWNER} explicitly (see that method), not because ownership is a
   * fourth access path of its own - deliberately the same formula {@link
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
            .countTopLevelByLibraryIdIn(libraries.stream().map(KnowledgeLibrary::getId).toList())
            .stream()
            .collect(
                Collectors.toMap(
                    DocumentRepository.LibraryDocumentCount::getLibraryId,
                    DocumentRepository.LibraryDocumentCount::getDocumentCount));
    Map<UUID, String> ownerNames = assetOwnerNames.of(libraries);
    // #684: the "Stand" column's last successful run, one grouped query for the whole page
    // (same shape as documentCounts above) - a library without any completed run stays null.
    Map<UUID, Instant> lastIndexedAt =
        indexingJobRepository
            .findLastCompletedByLibraryIdIn(
                libraries.stream().map(KnowledgeLibrary::getId).toList())
            .stream()
            .collect(
                Collectors.toMap(
                    IndexingJobRepository.LibraryLastCompleted::getLibraryId,
                    IndexingJobRepository.LibraryLastCompleted::getLastCompletedAt));
    // #1940: the newest run's own status, whatever it was - a failed last run is invisible in
    // lastIndexedAt above, which only ever moves on a success. Same one-query-per-page shape.
    Map<UUID, JobStatus> lastRunStatus =
        indexingJobRepository
            .findLastRunStatusByLibraryIdIn(
                libraries.stream().map(KnowledgeLibrary::getId).toList())
            .stream()
            .collect(
                Collectors.toMap(
                    IndexingJobRepository.LibraryLastRunStatus::getLibraryId,
                    status -> JobStatus.valueOf(status.getStatus())));

    Map<UUID, SuccessionFinding> succession = successionSource.findingsAmong(libraries, false);
    // #1931: the reach badge, one grouped query for the whole page like the counts above.
    Map<UUID, AssetReach> reach = accessService.reachOf(libraries);

    return libraries.stream()
        .map(
            library ->
                new LibrarySummary(
                    library,
                    roles.get(library.getId()),
                    documentCounts.getOrDefault(library.getId(), 0L),
                    ownerNames.get(library.getOwnerId()),
                    lastIndexedAt.get(library.getId()),
                    lastRunStatus.get(library.getId()),
                    succession.get(library.getId()),
                    reach.getOrDefault(library.getId(), AssetReach.NONE)))
        .toList();
  }

  public LibraryDetail getLibrary(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    AssetRole role =
        accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);
    return toLibraryDetail(library, role, caller.id());
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
    // absent from that unrelated request. Connector-owned fields are replaced when present and
    // left alone when absent.
    boolean replacesSourceConfiguration = hasSourceConfigurationFields(request);
    SourceSettings requestedSettings =
        requestedSettingsChange(library, request, replacesSourceConfiguration);
    SourceConnector connector = connectors.connector(library.getSourceType());
    SourceSettings validatedSettings =
        connectors.validateChange(library, requestedSettings, replacesSourceConfiguration);
    boolean replacesOwnSettings =
        Arrays.stream(SourceSettingField.values())
            .anyMatch(field -> field.isSetIn(requestedSettings));
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
    String previousSourcePath = library.getSourcePath();
    String previousSourceUrl = library.getSourceUrl();
    String previousSourceProxy = library.getSourceProxy();
    String previousSourceCredentials = library.getSourceCredentials();
    boolean previousSourceInsecureSsl = library.isSourceInsecureSsl();
    Map<String, Object> previousSettingsState = connector.settingsState(library);
    // The shell refuses listing while the succession is open and asks the share cap; a change of
    // listed writes its history interval and ASSET_VISIBILITY_CHANGED there.
    library.rename(normalizedName, request.description());
    shellService.changeListed(library, listed, currentUserId);
    if (replacesSchedule) {
      library.updateSchedule(validatedSchedule.enabled(), validatedSchedule.cron());
    }
    if (replacesSourceConfiguration) {
      library.updateSourceConfiguration(
          validatedSettings.sourcePath(),
          validatedSettings.sourceUrl(),
          validatedSettings.sourceProxy(),
          validatedSettings.sourceCredentials(),
          validatedSettings.sourceInsecureSsl());
      // The discard a host change performs (see requestedSettingsChange's Javadoc) is
      // a security invariant and must not depend on the dirty check: with the key missing the
      // attribute already reads null, so only an erasure on the column itself removes the
      // ciphertext the returning key would otherwise send to the new host (#1806). A change that
      // keeps the origin is deliberately not erased - there the same null means "unreadable, leave
      // it alone".
      if (validatedSettings.sourceCredentials() == null
          && previousSourceUrl != null
          && !SourceOriginMatcher.sameOrigin(previousSourceUrl, validatedSettings.sourceUrl())) {
        libraryRepository.eraseSourceCredentials(library.getId());
      }
    }
    connector.applyChange(library, validatedSettings);
    KnowledgeLibrary updated = libraryRepository.save(library);
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
    // LIBRARY_CHANGED (name/description) nor ASSET_VISIBILITY_CHANGED (listed) fires for it,
    // since the edit dialog (#516) resends name/description/listed unchanged.
    // Only the set of changed fields is recorded, never their values - sourceCredentials in
    // particular must never appear in the log (ADR-0018, Entscheidung 4), so unlike
    // LIBRARY_CHANGED's before/after this event carries no value at all, not even a redacted one.
    if (replacesSourceConfiguration || replacesOwnSettings) {
      List<String> changedSourceFields = new ArrayList<>();
      if (!Objects.equals(previousSourcePath, updated.getSourcePath())) {
        changedSourceFields.add("sourcePath");
      }
      boolean sourceUrlChanged = !Objects.equals(previousSourceUrl, updated.getSourceUrl());
      if (sourceUrlChanged) {
        changedSourceFields.add("sourceUrl");
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
      // Connector-owned settings leave the same trail as the connection fields; the connector
      // discards whatever run state the change invalidates.
      Map<String, Object> currentSettingsState = connector.settingsState(updated);
      Set<String> changedSettings = new LinkedHashSet<>();
      for (Map.Entry<String, Object> previous : previousSettingsState.entrySet()) {
        if (!Objects.equals(previous.getValue(), currentSettingsState.get(previous.getKey()))) {
          changedSettings.add(previous.getKey());
        }
      }
      changedSourceFields.addAll(changedSettings);
      connector.onSourceChanged(updated, sourceUrlChanged, changedSettings);
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
        updated, accessService.effectiveRole(updated, currentUserId, systemAdmin), currentUserId);
  }

  /**
   * Sets a connector library's share cap (#797) - {@code SYSTEM_ADMIN} only, rejected for {@code
   * UPLOAD}. A cap that now forbids what the library currently carries takes it back in the same
   * transaction (#1931, ADR-0037 Entscheidung 5): the grant to "Alle Konten" is revoked through the
   * ordinary grant path, {@code listed} is cleared through the asset shell. Both are recorded
   * separately from the cap change itself ({@code CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED}), so
   * nothing is ever left "verletzt, aber geduldet".
   */
  @Transactional
  public LibraryDetail updateShareCap(
      UUID libraryId, boolean allAccountsGrantAllowed, boolean listedCap, CurrentUser caller) {
    if (!caller.isSystemAdmin()) {
      throw new AccessDeniedException(
          "Nur die Systemverwaltung darf die Freigabe-Obergrenze einer Bibliothek setzen");
    }
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    if (!hasIndexingRun(library)) {
      throw new ValidationException(
          "Upload-Bibliotheken tragen keine Freigabe-Obergrenze - jedes Dokument wird ohnehin"
              + " einzeln von der Eigentümerin kuratiert");
    }
    boolean previousCap = library.isAllAccountsGrantAllowed();
    boolean previousListedCap = library.isListedCap();
    library.updateShareCap(allAccountsGrantAllowed, listedCap);
    KnowledgeLibrary saved = libraryRepository.save(library);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(saved.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, saved.getId(), saved.getName())
            .before(Map.of("allAccountsGrantAllowed", previousCap, "listedCap", previousListedCap))
            .after(
                Map.of("allAccountsGrantAllowed", allAccountsGrantAllowed, "listedCap", listedCap))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    if (!allAccountsGrantAllowed) {
      grantService.revokeAllAccountsGrantForLoweredCap(saved, caller.id());
    }
    if (!listedCap) {
      shellService.clearListedForLoweredCap(saved, caller.id());
    }
    return toLibraryDetail(saved, AssetRole.OWNER, caller.id());
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
    if (!hasIndexingRun(library)) {
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
      // chunks (DocumentIngestService#storeChunks) and its conditional status-transition UPDATE
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

    // The history tables carry no foreign key on the asset (ADR-0016): the shell closes the open
    // grant, reach and ownership intervals before the row is gone.
    shellService.registerDeleted(library, currentUserId);

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
   *
   * <p><b>Pflege-Anker seit #1069.</b> {@code missingMetadataField} narrows the list to exactly the
   * documents the Pflege-Anker counts for that core field - see {@link
   * #listDocumentsWithoutMetadataValue}, which then owns the whole request.
   */
  public LibraryDocumentPage listDocuments(
      UUID libraryId,
      CurrentUser caller,
      String q,
      UUID folderId,
      String missingMetadataField,
      Pageable pageable) {
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);

    // #821 review round 1, finding 3: validated unconditionally, before either branch below - a
    // folderId from another library or one that does not exist answers 404 whether or not q is
    // also set, instead of q silently bypassing the check.
    if (folderId != null) {
      requireFolderInLibrary(libraryId, folderId);
    }

    boolean searching = q != null && !q.isBlank();
    if (missingMetadataField != null && !missingMetadataField.isBlank()) {
      return listDocumentsWithoutMetadataValue(
          libraryId, searching ? q : null, missingMetadataField.strip(), pageable);
    }
    if (searching) {
      // #1184 (ADR-0022, Entscheidung 5): a hit on an attachment's file name must surface its
      // top-level parent - resolve every matching attachment row up its parentDocumentId chain
      // first, then page over top-level documents matching by own name or by subtree.
      Set<UUID> attachmentRootIds =
          topLevelRootsOf(
              documentRepository
                  .findByLibraryIdAndParentDocumentIdIsNotNullAndFileNameContainingIgnoreCase(
                      libraryId, q));
      Page<Document> page =
          documentRepository.searchTopLevelByFileNameOrAttachmentRoot(
              libraryId, escapeLike(q), attachmentRootIds, pageable);
      Map<UUID, LibraryFolder> foldersById =
          LibraryFolderPaths.loadFoldersById(folderRepository, libraryId);
      return new LibraryDocumentPage(
          withAttachments(page.getContent(), foldersById),
          pageable.getPageNumber(),
          pageable.getPageSize(),
          page.getTotalElements(),
          List.of(),
          List.of(),
          null);
    }

    Page<Document> page =
        folderId == null
            ? documentRepository.findByLibraryIdAndFolderIdIsNullAndParentDocumentIdIsNull(
                libraryId, pageable)
            : documentRepository.findByLibraryIdAndFolderId(libraryId, folderId, pageable);
    Map<UUID, LibraryFolder> foldersById =
        LibraryFolderPaths.loadFoldersById(folderRepository, libraryId);

    return new LibraryDocumentPage(
        withAttachments(page.getContent(), foldersById),
        pageable.getPageNumber(),
        pageable.getPageSize(),
        page.getTotalElements(),
        foldersOf(libraryId, folderId),
        breadcrumbOf(folderId, foldersById),
        folderId);
  }

  /**
   * The Pflege-Anker's list (#1069): exactly the document rows the anchor counts as "ohne Wert" for
   * {@code fieldKey} - one entry per row, attachments included and listed in their own right, so
   * the number in the anchor and the length of this list are the same figure and every entry a
   * Sammelzuweisung touches is genuinely open. Bibliotheksweit like a search (the anchor counts the
   * whole library) and combinable with {@code q}; folders and breadcrumb stay empty.
   */
  private LibraryDocumentPage listDocumentsWithoutMetadataValue(
      UUID libraryId, String q, String fieldKey, Pageable pageable) {
    if (CoreMetadataField.fromKey(fieldKey).isEmpty()) {
      throw new ValidationException("Unbekanntes Metadatenfeld: " + fieldKey);
    }
    Page<Document> page =
        documentRepository.searchWithoutMetadataValue(
            libraryId, q == null ? "" : escapeLike(q), fieldKey, DocumentStatus.INDEXED, pageable);
    Map<UUID, LibraryFolder> foldersById =
        LibraryFolderPaths.loadFoldersById(folderRepository, libraryId);
    return new LibraryDocumentPage(
        page.getContent().stream()
            .map(document -> toLibraryDocumentEntry(document, foldersById))
            .toList(),
        pageable.getPageNumber(),
        pageable.getPageSize(),
        page.getTotalElements(),
        List.of(),
        List.of(),
        null);
  }

  /**
   * Expands a page of top-level documents into the flat entry list {@code LibraryDocumentPage}
   * promises (#1184): each document immediately followed by its complete attachment subtree,
   * depth-first, siblings in {@code filePath} order (which embeds the extraction-order index for
   * mail attachments, ADR-0022 Entscheidung 2). Attachments never count towards paging - they ride
   * along on their parent's page. Children are loaded once per nesting level, not once per parent.
   */
  private List<LibraryDocumentEntry> withAttachments(
      List<Document> parents, Map<UUID, LibraryFolder> foldersById) {
    Map<UUID, List<Document>> childrenByParent = new HashMap<>();
    List<UUID> level = parents.stream().map(Document::getId).toList();
    while (!level.isEmpty()) {
      List<Document> children =
          documentRepository.findByParentDocumentIdInOrderByFilePathAsc(level);
      if (children.isEmpty()) {
        break;
      }
      for (Document child : children) {
        childrenByParent
            .computeIfAbsent(child.getParentDocumentId(), k -> new ArrayList<>())
            .add(child);
      }
      level = children.stream().map(Document::getId).toList();
    }
    List<LibraryDocumentEntry> entries = new ArrayList<>();
    for (Document parent : parents) {
      appendSubtree(parent, childrenByParent, foldersById, entries);
    }
    return entries;
  }

  private void appendSubtree(
      Document document,
      Map<UUID, List<Document>> childrenByParent,
      Map<UUID, LibraryFolder> foldersById,
      List<LibraryDocumentEntry> entries) {
    entries.add(toLibraryDocumentEntry(document, foldersById));
    for (Document child : childrenByParent.getOrDefault(document.getId(), List.of())) {
      appendSubtree(child, childrenByParent, foldersById, entries);
    }
  }

  /**
   * Walks each attachment in {@code attachments} up its {@code parentDocumentId} chain to the
   * top-level document ({@code parentDocumentId == null}) it transitively belongs to - one {@code
   * findAllById} per nesting level. A parent that no longer resolves (deleted concurrently) simply
   * drops out rather than failing the search. {@code visited} guards against a cyclic {@code
   * parent_document_id} chain (nothing in the schema forbids one) hanging the request thread - a
   * cycle simply never reaches a root and contributes nothing.
   */
  private Set<UUID> topLevelRootsOf(List<DocumentRepository.AttachmentParentRef> attachments) {
    Set<UUID> roots = new HashSet<>();
    Set<UUID> visited = new HashSet<>();
    Set<UUID> parentIds =
        attachments.stream()
            .map(DocumentRepository.AttachmentParentRef::getParentDocumentId)
            .collect(Collectors.toSet());
    while (!parentIds.isEmpty()) {
      visited.addAll(parentIds);
      Set<UUID> next = new HashSet<>();
      for (Document parent : documentRepository.findAllById(parentIds)) {
        if (parent.getParentDocumentId() == null) {
          roots.add(parent.getId());
        } else if (!visited.contains(parent.getParentDocumentId())) {
          next.add(parent.getParentDocumentId());
        }
      }
      parentIds = next;
    }
    return roots;
  }

  /**
   * Backslash-escapes LIKE metacharacters ({@code \}, {@code %}, {@code _}) for {@code
   * DocumentRepository#searchTopLevelByFileNameOrAttachmentRoot}'s hand-written LIKE - preserving
   * the literal-match semantics the derived {@code ...ContainingIgnoreCase} finders apply
   * automatically (#517 review, nit 3).
   */
  private static String escapeLike(String q) {
    return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
   * A library's quellentyp is required at creation (ADR-0018); its connector validates the
   * configuration - the 400-before-insert half of {@code
   * chk_knowledge_libraries_source_configuration}. A connector-owned field on a library of another
   * type is refused naming its owner. {@code sourceInsecureSsl} defaults to {@code false} when
   * omitted.
   */
  private SourceConfiguration validateSourceConfiguration(LibraryCreation request) {
    DocumentSourceType sourceType = request.sourceType();
    if (sourceType == null) {
      throw new ValidationException("sourceType ist erforderlich");
    }
    SourceSettings requested =
        new SourceSettings(
            blankToNull(request.sourcePath()),
            blankToNull(request.sourceUrl() == null ? null : request.sourceUrl().toString()),
            blankToNull(request.sourceProxy()),
            blankToNull(request.sourceCredentials()),
            Boolean.TRUE.equals(request.sourceInsecureSsl()),
            request.confluenceEdition(),
            request.confluenceSpaces(),
            request.confluenceFullSyncIntervalDays(),
            request.s3Settings());
    SourceSettings validated = connectors.validateNew(sourceType, requested);
    return new SourceConfiguration(sourceType, validated);
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
   * The change {@code request} asks for, as its connector validates it: the connection fields when
   * {@code replacesConnection} (issue #476, review finding 4 - password rotation or moving a crawl
   * target must not force recreating the library), and every connector-owned field as sent. {@code
   * sourceType} is always the library's own, never taken from the request.
   *
   * <p>{@code sourceCredentials} falls back to the library's currently stored value when the
   * request omits it <em>and</em> the new {@code sourceUrl} still names the same origin (scheme,
   * host and port) as the currently stored one (issue #516, PR #542 review finding 1): credentials
   * are write-only (never returned by any API response, ADR-0018), so a client editing e.g. only
   * the path portion of {@code sourceUrl} has no value it could resend even if it wanted to, and
   * without this fallback that edit alone would silently wipe an unrelated, previously configured
   * credential. The fallback is deliberately restricted to the same origin: {@code
   * AutoindexCrawlerService} sends the stored {@code Authorization} header preemptively on the very
   * first request (RFC 7617 does not require a 401 challenge first), so a caller who does not know
   * a configured credential could otherwise redirect it to a host they control simply by changing
   * {@code sourceUrl} and leaving the credentials field blank - turning "must know the credential"
   * into "can exfiltrate the credential". A host change intentionally drops the stored credential
   * instead (matching the pre-fallback behaviour of #476), forcing the caller to re-enter it for
   * the new host. There is deliberately no way to explicitly clear a stored credential while
   * keeping the same origin - blank input is indistinguishable from "leave unchanged" by design.
   */
  private SourceSettings requestedSettingsChange(
      KnowledgeLibrary library, LibraryUpdate request, boolean replacesConnection) {
    if (!replacesConnection) {
      return new SourceSettings(
          null,
          null,
          null,
          null,
          false,
          request.confluenceEdition(),
          request.confluenceSpaces(),
          request.confluenceFullSyncIntervalDays(),
          request.s3Settings());
    }
    String sourceUrl =
        blankToNull(request.sourceUrl() == null ? null : request.sourceUrl().toString());
    String sourceCredentials = blankToNull(request.sourceCredentials());
    if (sourceCredentials == null
        && SourceOriginMatcher.sameOrigin(library.getSourceUrl(), sourceUrl)) {
      sourceCredentials = library.getSourceCredentials();
    }
    return new SourceSettings(
        blankToNull(request.sourcePath()),
        sourceUrl,
        blankToNull(request.sourceProxy()),
        sourceCredentials,
        Boolean.TRUE.equals(request.sourceInsecureSsl()),
        request.confluenceEdition(),
        request.confluenceSpaces(),
        request.confluenceFullSyncIntervalDays(),
        request.s3Settings());
  }

  /** Whether a run fills {@code library}, as its connector describes it. */
  private boolean hasIndexingRun(KnowledgeLibrary library) {
    return connectors.descriptor(library.getSourceType()).indexingRun();
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
    if (frequency != ScheduleFrequency.DISABLED
        && !connectors.descriptor(sourceType).indexingRun()) {
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

  /** A validated {@link LibraryCreation}'s source type and settings, for the entity factories. */
  private record SourceConfiguration(DocumentSourceType sourceType, SourceSettings settings) {

    String sourcePath() {
      return settings.sourcePath();
    }

    String sourceUrl() {
      return settings.sourceUrl();
    }

    String sourceProxy() {
      return settings.sourceProxy();
    }

    String sourceCredentials() {
      return settings.sourceCredentials();
    }

    boolean sourceInsecureSsl() {
      return settings.sourceInsecureSsl();
    }
  }

  /**
   * Generates a fresh webhook secret (#1140) - MANAGER or above, like every other change of the
   * source configuration - stores it encrypted and returns the plaintext exactly once. A second
   * call rotates: the previous secret stops authenticating with the commit. The audit entry names
   * the field, never the value (ADR-0018, Entscheidung 4).
   */
  @Transactional
  public String generateConfluenceWebhookSecret(UUID libraryId, CurrentUser caller) {
    return generatePushSecret(libraryId, caller, PushIntake.WEBHOOK_SECRET);
  }

  /** Removes the webhook secret (#1140): the endpoint rejects every call from now on. */
  @Transactional
  public void removeConfluenceWebhookSecret(UUID libraryId, CurrentUser caller) {
    removePushSecret(libraryId, caller, PushIntake.WEBHOOK_SECRET);
  }

  /**
   * Generates (or rotates) the event token (ADR-0027, Entscheidung 6) and returns it exactly once -
   * the same secret column, encryption path and audit trail as the webhook secret.
   */
  @Transactional
  public String generateS3EventsToken(UUID libraryId, CurrentUser caller) {
    return generatePushSecret(libraryId, caller, PushIntake.EVENT_TOKEN);
  }

  /** Removes the event token: the library's event endpoint rejects every call from now on. */
  @Transactional
  public void removeS3EventsToken(UUID libraryId, CurrentUser caller) {
    removePushSecret(libraryId, caller, PushIntake.EVENT_TOKEN);
  }

  private String generatePushSecret(UUID libraryId, CurrentUser caller, PushIntake intake) {
    KnowledgeLibrary library = requireLibraryWithPushIntake(libraryId, caller, intake);
    byte[] random = new byte[WEBHOOK_SECRET_BYTES];
    secureRandom.nextBytes(random);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    library.setWebhookSecret(secret);
    libraryRepository.save(library);
    recordPushSecretChange(library, caller, intake);
    return secret;
  }

  private void removePushSecret(UUID libraryId, CurrentUser caller, PushIntake intake) {
    KnowledgeLibrary library = requireLibraryWithPushIntake(libraryId, caller, intake);
    // Whether there is a secret to revoke is decided by the stored ciphertext, not by the entity
    // attribute: with the key missing the attribute reads null for a secret that still
    // authenticates once the key returns (#1806). The erasure carries the revocation; the entity
    // assignment below only keeps the loaded instance consistent with it.
    if (libraryRepository.eraseWebhookSecret(libraryId) == 0) {
      return;
    }
    library.setWebhookSecret(null);
    libraryRepository.save(library);
    recordPushSecretChange(library, caller, intake);
  }

  private KnowledgeLibrary requireLibraryWithPushIntake(
      UUID libraryId, CurrentUser caller, PushIntake intake) {
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    if (connectors.descriptor(library.getSourceType()).pushIntake() != intake) {
      throw new ValidationException(intake.unavailableMessage(connectors.ownerOf(intake)));
    }
    return library;
  }

  /** The audit names the secret's field, never the value. */
  private void recordPushSecretChange(
      KnowledgeLibrary library, CurrentUser caller, PushIntake intake) {
    List<String> changedFields = List.of(intake.auditField());
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.LIBRARY_SOURCE_UPDATED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.getName())
            .before(Map.of("changedFields", changedFields))
            .after(Map.of("changedFields", changedFields))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /**
   * Loads a library and enforces the organization boundary, treating a library from another
   * organization as not found - mirrors {@code SpaceService#loadSpace}. Applies to system admins as
   * well; the boundary is not overstepped even to reveal existence.
   */
  /** The library's owner as the permission model names a subject - person or group. */
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

  private LibraryDetail toLibraryDetail(KnowledgeLibrary library, AssetRole myRole, UUID userId) {
    // #1184: top-level documents only, consistent with the document list's own parent-level
    // totalElements - attachments are visible inside their parent's group, not in this count.
    long documentCount =
        documentRepository.countByLibraryIdAndParentDocumentIdIsNull(library.getId());
    // #507/#119: sourcePath/sourceUrl/sourceProxy/schedule/storage quota are administration
    // detail - internal server paths, crawl targets and bestand size - gated at MANAGER, not
    // exposed to a mere VIEWER (or even EDITOR) of an organization-wide library.
    // sourceCredentials is deliberately never read here - ADR-0018 makes it a write-only field
    // that appears in no API response, not even for the library's own owner.
    LibraryManagementDetail managementDetail =
        myRole.atLeast(AssetRole.MANAGER)
            ? toManagementDetail(library)
            : LibraryManagementDetail.EMPTY;
    // #1278 review: myRole alone cannot tell a client whether PUT .../diagnostics-lock will
    // succeed - it bypasses to OWNER for a system admin, holdsIndependentOwnerRole never does.
    boolean diagnosticsLockToggleable = accessService.holdsIndependentOwnerRole(library, userId);
    return new LibraryDetail(
        library,
        myRole,
        documentCount,
        managementDetail,
        diagnosticsLockToggleable,
        accessService.reachOf(List.of(library)).get(library.getId()),
        // #1941: who is responsible for a library is not a secret from its readers - the same
        // resolution the overview uses, and the same silence about a name it may not disclose.
        assetOwnerNames.of(List.of(library)).get(library.getOwnerId()));
  }

  private LibraryManagementDetail toManagementDetail(KnowledgeLibrary library) {
    SourceConnectorDescriptor descriptor = connectors.descriptor(library.getSourceType());
    // #485: schedule/lastScheduledRunsFailed stay null for a library without a run, which cannot
    // carry a schedule at all (chk_knowledge_libraries_schedule) - nextRunAt would otherwise leak
    // the same "does an internal crawl target exist" detail #507 already gates.
    LibraryScheduleDetail schedule = null;
    Boolean lastScheduledRunsFailed = null;
    if (descriptor.indexingRun()) {
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
    // #1140, ADR-0027 Entscheidung 6: whether the push secret is set - shown once, at generation.
    Boolean pushSecretSet =
        descriptor.pushIntake() == null ? null : library.getWebhookSecret() != null;
    return new LibraryManagementDetail(
        library.getSourcePath(),
        library.getSourceUrl(),
        library.getSourceProxy(),
        library.isSourceInsecureSsl(),
        // PR #542 review, nit 3: a non-secret yes/no, not the credential itself (ADR-0018) - lets
        // a client phrase an accurate "leave blank to keep the current credential" hint only when
        // one is actually stored.
        library.getSourceCredentials() != null,
        descriptor.pushIntake() == PushIntake.WEBHOOK_SECRET ? pushSecretSet : null,
        descriptor.pushIntake() == PushIntake.EVENT_TOKEN ? pushSecretSet : null,
        descriptor.fullSyncInterval() == null ? null : library.getConfluenceFullSyncIntervalDays(),
        // #1200: the instance-wide rhythm in whole days, so the schedule dialog can name the
        // default instead of hard-coding it; a sub-day interval still reads as one day.
        descriptor.fullSyncInterval() == null
            ? null
            : (int) Math.max(1, descriptor.fullSyncInterval().toDays()),
        schedule,
        lastScheduledRunsFailed,
        storageQuotaService.quotaBytes(),
        storageQuotaService.usedBytes(library.getId()),
        externalAccessService.describe(library),
        // #797: a library without a run never carries a cap narrower than the unrestricted default
        // (chk_knowledge_libraries_share_cap_upload_unrestricted) - null here rather than the
        // always-true value keeps a MANAGER from reading a ceiling into a library that in fact has
        // none.
        descriptor.indexingRun() ? library.isAllAccountsGrantAllowed() : null,
        descriptor.indexingRun() ? library.isListedCap() : null);
  }

  private LibraryDocumentEntry toLibraryDocumentEntry(
      Document document, Map<UUID, LibraryFolder> foldersById) {
    return new LibraryDocumentEntry(
        document, LibraryFolderPaths.pathOf(document.getFolderId(), foldersById));
  }
}
