package io.opaa.prompt;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.Capability;
import io.opaa.asset.Asset;
import io.opaa.asset.AssetAuthorization;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetOwnerNames;
import io.opaa.asset.AssetShellService;
import io.opaa.asset.AssetSuccessionSource;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.SuccessionFinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create, read, change and delete prompt libraries. Every rights question goes to the asset shell:
 * the list is the formula's ({@link AssetAccessService#readableAssetIds}), a single library the
 * administration's ({@link AssetAuthorization#requireRole}, a system administrator counting as
 * {@code OWNER}) - {@code VIEWER} to read it, {@code MANAGER} to change it, {@code OWNER} to delete
 * it. Grants, release level, ownership and succession are the shell's alone; this class calls them
 * from its own create, update and delete paths.
 */
@Service
@Transactional(readOnly = true)
public class PromptLibraryService {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  private final PromptLibraryRepository libraryRepository;
  private final PromptRepository promptRepository;
  private final CapabilityService capabilityService;
  private final AssetAuthorization authorization;
  private final AssetAccessService accessService;
  private final AssetGrantService grantService;
  private final AssetShellService shellService;
  private final AssetOwnerNames ownerNames;
  private final AssetSuccessionSource successionSource;
  private final AuditEventRecorder auditEventRecorder;

  PromptLibraryService(
      PromptLibraryRepository libraryRepository,
      PromptRepository promptRepository,
      CapabilityService capabilityService,
      AssetAuthorization authorization,
      AssetAccessService accessService,
      AssetGrantService grantService,
      AssetShellService shellService,
      AssetOwnerNames ownerNames,
      AssetSuccessionSource successionSource,
      AuditEventRecorder auditEventRecorder) {
    this.libraryRepository = libraryRepository;
    this.promptRepository = promptRepository;
    this.capabilityService = capabilityService;
    this.authorization = authorization;
    this.accessService = accessService;
    this.grantService = grantService;
    this.shellService = shellService;
    this.ownerNames = ownerNames;
    this.successionSource = successionSource;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * Creates a prompt library owned by the caller or, for {@link AssetOwnerType#GROUP}, by one of
   * the caller's groups. Needs {@link Capability#CREATE_PROMPT_LIBRARY}; unlisted unless the
   * request lists it.
   */
  @Transactional
  public PromptLibraryView create(PromptLibraryCreation request, CurrentUser caller) {
    capabilityService.requireCapability(caller, Capability.CREATE_PROMPT_LIBRARY);
    String name = validateName(request.name());
    String description = validateDescription(request.description());
    AssetVisibility visibility =
        request.visibility() != null ? request.visibility() : AssetVisibility.PRIVATE;
    boolean listed = Boolean.TRUE.equals(request.listed());

    PromptLibrary library;
    if (request.ownerType() == AssetOwnerType.GROUP) {
      if (request.ownerId() == null) {
        throw new ValidationException("ownerId ist erforderlich, wenn ownerType GROUP ist");
      }
      grantService.requireOwnableGroup(request.ownerId(), PromptLibrary.ASSET_TYPE, caller);
      library =
          PromptLibrary.ownedByGroup(
              caller.organizationId(), name, description, request.ownerId(), visibility, listed);
    } else {
      library =
          PromptLibrary.ownedByUser(
              caller.organizationId(), name, description, caller.id(), visibility, listed);
    }
    PromptLibrary saved = libraryRepository.save(library);
    shellService.registerCreated(saved, caller.id(), auditPayload(saved));
    return view(saved, AssetRole.OWNER);
  }

  /**
   * Every prompt library the caller may read by the formula, by name - the same set whose prompts
   * {@link PromptService} lets them read. {@code myRole} is the formula's, at least {@code VIEWER}.
   */
  public List<PromptLibraryView> list(CurrentUser caller) {
    Set<UUID> readable =
        accessService.readableAssetIds(
            PromptLibrary.ASSET_TYPE, caller.id(), caller.organizationId());
    List<PromptLibrary> libraries =
        libraryRepository.findAllById(readable).stream()
            .sorted(
                Comparator.comparing(PromptLibrary::getName).thenComparing(PromptLibrary::getId))
            .toList();
    Map<UUID, AssetRole> roles =
        accessService.effectiveRoles(
            PromptLibrary.ASSET_TYPE,
            readable,
            caller.id(),
            libraries.stream()
                .filter(Asset::isOrganizationWide)
                .map(Asset::getId)
                .collect(Collectors.toSet()));
    Map<UUID, Long> counts =
        promptRepository.countByLibraryIdIn(readable).stream()
            .collect(
                Collectors.toMap(
                    PromptRepository.LibraryPromptCount::getLibraryId,
                    PromptRepository.LibraryPromptCount::getPromptCount));
    Map<UUID, String> names = ownerNames.of(libraries);
    Map<UUID, SuccessionFinding> succession = successionSource.findingsAmong(libraries, false);
    List<PromptLibraryView> views = new ArrayList<>(libraries.size());
    for (PromptLibrary library : libraries) {
      views.add(
          new PromptLibraryView(
              library,
              Objects.requireNonNullElse(roles.get(library.getId()), AssetRole.VIEWER),
              counts.getOrDefault(library.getId(), 0L),
              names.get(library.getOwnerId()),
              succession.get(library.getId())));
    }
    return views;
  }

  public PromptLibraryView get(UUID libraryId, CurrentUser caller) {
    PromptLibrary library = load(libraryId, caller);
    AssetRole role =
        authorization.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);
    return view(library, role);
  }

  /**
   * Replaces name, description and reach ({@code MANAGER}). A wider reach is refused while the
   * succession is open - the shell's rule; a rename writes {@code PROMPT_LIBRARY_CHANGED} with the
   * names of the changed fields, never their values.
   */
  @Transactional
  public PromptLibraryView update(UUID libraryId, PromptLibraryUpdate request, CurrentUser caller) {
    PromptLibrary library = load(libraryId, caller);
    AssetRole role =
        authorization.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    String name = validateName(request.name());
    String description = validateDescription(request.description());
    if (request.visibility() == null) {
      throw new ValidationException("visibility ist erforderlich");
    }
    shellService.changeReach(library, request.visibility(), request.listed(), caller.id());

    List<String> changedFields = new ArrayList<>();
    if (!Objects.equals(library.getName(), name)) {
      changedFields.add("name");
    }
    if (!Objects.equals(library.getDescription(), description)) {
      changedFields.add("description");
    }
    if (!changedFields.isEmpty()) {
      library.rename(name, description);
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(library.getOrganizationId())
              .actor(caller.id())
              .type(AuditEventType.PROMPT_LIBRARY_CHANGED)
              .object(AuditObjectType.PROMPT_LIBRARY, library.getId(), name)
              .before(Map.of("changedFields", changedFields))
              .after(Map.of("changedFields", changedFields))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return view(libraryRepository.save(library), role);
  }

  /**
   * Deletes the library ({@code OWNER}) with its prompts; grants and space associations go with the
   * shell row, the open history intervals are closed first.
   */
  @Transactional
  public void delete(UUID libraryId, CurrentUser caller) {
    PromptLibrary library = load(libraryId, caller);
    authorization.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.OWNER);
    long promptsRemoved = promptRepository.countByLibraryId(library.getId());
    shellService.registerDeleted(library, caller.id());
    Map<String, Object> payload = auditPayload(library);
    payload.put("promptsRemoved", promptsRemoved);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.PROMPT_LIBRARY_DELETED)
            .object(AuditObjectType.PROMPT_LIBRARY, library.getId(), library.getName())
            .before(payload)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    libraryRepository.delete(library);
  }

  /**
   * The prompt library of the caller's organization with this id; an unknown id and one of another
   * organization answer the same {@code 404}.
   */
  PromptLibrary load(UUID libraryId, CurrentUser caller) {
    Asset asset = authorization.load(PromptLibrary.ASSET_TYPE, libraryId, caller.organizationId());
    if (Hibernate.unproxy(asset) instanceof PromptLibrary library) {
      return library;
    }
    throw new NotFoundException("Prompt-Bibliothek nicht gefunden");
  }

  private PromptLibraryView view(PromptLibrary library, AssetRole role) {
    return new PromptLibraryView(
        library,
        role,
        promptRepository.countByLibraryId(library.getId()),
        ownerNames.of(List.of(library)).get(library.getOwnerId()),
        successionSource.findingsAmong(List.of(library), false).get(library.getId()));
  }

  private static Map<String, Object> auditPayload(PromptLibrary library) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", library.getName());
    payload.put("visibility", library.getVisibility().name());
    payload.put("listed", library.isListed());
    return payload;
  }

  private static String validateName(String name) {
    String normalized = name == null ? "" : name.strip();
    if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
      throw new ValidationException(
          "Der Name ist erforderlich und hat höchstens " + MAX_NAME_LENGTH + " Zeichen.");
    }
    return normalized;
  }

  private static String validateDescription(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ValidationException(
          "Die Beschreibung hat höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen.");
    }
    return description;
  }
}
