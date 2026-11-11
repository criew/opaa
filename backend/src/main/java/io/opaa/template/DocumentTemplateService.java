package io.opaa.template;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.Capability;
import io.opaa.asset.Asset;
import io.opaa.asset.AssetAuthorization;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetOwnerNames;
import io.opaa.asset.AssetResponsibilityService;
import io.opaa.asset.AssetResponsibilityView;
import io.opaa.asset.AssetShellService;
import io.opaa.asset.AssetSuccessionSource;
import io.opaa.asset.ResponsibleUnit;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.MalwareScanService;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetReach;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.SuccessionFinding;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Create, read, change and delete document templates. Every rights question goes to the asset
 * shell, as for prompt libraries: the list is the formula's, a single template the administration's
 * ({@code VIEWER} to read and download it, {@code EDITOR} to replace its file, {@code MANAGER} to
 * rename it and {@code OWNER} to delete it, a system administrator counting as {@code OWNER}).
 * Every path answers 404 while the switch "Vorlagen" is off.
 *
 * <p>An upload is admitted by {@link DocxTemplateInspector} and the malware scan before anything is
 * stored, outside any transaction, so neither holds a database connection; the write then checks
 * switch and rights again. The file is stored as received and never rendered here.
 *
 * <p>Every template names a responsible unit ({@link AssetResponsibilityService}); the system
 * administration may lock a single template, which then reaches no agent run ({@link #lockedAmong})
 * and stays manageable.
 */
@Service
@Transactional(readOnly = true)
public class DocumentTemplateService {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  private final DocumentTemplateRepository repository;
  private final DocumentTemplateFiles files;
  private final DocxTemplateInspector inspector;
  private final DocumentTemplateSettingsService settings;
  private final MalwareScanService malwareScanService;
  private final CapabilityService capabilityService;
  private final AssetAuthorization authorization;
  private final AssetAccessService accessService;
  private final AssetGrantService grantService;
  private final AssetShellService shellService;
  private final AssetOwnerNames ownerNames;
  private final AssetSuccessionSource successionSource;
  private final AuditEventRecorder auditEventRecorder;
  private final AssetResponsibilityService responsibilityService;
  private final TransactionTemplate writes;

  DocumentTemplateService(
      DocumentTemplateRepository repository,
      DocumentTemplateFiles files,
      DocxTemplateInspector inspector,
      DocumentTemplateSettingsService settings,
      MalwareScanService malwareScanService,
      CapabilityService capabilityService,
      AssetAuthorization authorization,
      AssetAccessService accessService,
      AssetGrantService grantService,
      AssetShellService shellService,
      AssetOwnerNames ownerNames,
      AssetSuccessionSource successionSource,
      AuditEventRecorder auditEventRecorder,
      AssetResponsibilityService responsibilityService,
      PlatformTransactionManager transactionManager) {
    this.responsibilityService = responsibilityService;
    this.writes = new TransactionTemplate(transactionManager);
    this.repository = repository;
    this.files = files;
    this.inspector = inspector;
    this.settings = settings;
    this.malwareScanService = malwareScanService;
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
   * Creates a template owned by the caller or, for {@link AssetOwnerType#GROUP}, by one of the
   * caller's groups. Needs {@link Capability#CREATE_TEMPLATE}.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public DocumentTemplateView create(DocumentTemplateCreation request, CurrentUser caller) {
    requireMayCreate(request, caller);
    String name = validateName(request.name());
    String description = validateDescription(request.description());
    ResponsibleUnit responsible = responsibilityService.requireValid(request.responsible(), caller);
    Admitted admitted = admit(request.file(), caller.organizationId());
    return writes.execute(
        status -> {
          requireMayCreate(request, caller);
          DocumentTemplate template =
              request.ownerType() == AssetOwnerType.GROUP
                  ? DocumentTemplate.ownedByGroup(
                      caller.organizationId(),
                      name,
                      description,
                      request.ownerId(),
                      admitted.file())
                  : DocumentTemplate.ownedByUser(
                      caller.organizationId(), name, description, caller.id(), admitted.file());
          DocumentTemplate saved = repository.saveAndFlush(template);
          files.store(saved.getId(), admitted.content());
          shellService.registerCreated(saved, caller.id(), auditPayload(saved));
          responsibilityService.establish(saved, responsible);
          return view(saved, AssetRole.OWNER, caller);
        });
  }

  private void requireMayCreate(DocumentTemplateCreation request, CurrentUser caller) {
    settings.requireEnabled();
    capabilityService.requireCapability(caller, Capability.CREATE_TEMPLATE);
    if (request.ownerType() == AssetOwnerType.GROUP && request.ownerId() == null) {
      throw new ValidationException("ownerId ist erforderlich, wenn ownerType GROUP ist");
    }
    if (request.ownerType() == AssetOwnerType.GROUP) {
      grantService.requireOwnableGroup(request.ownerId(), DocumentTemplate.ASSET_TYPE, caller);
    }
  }

  /** Every template the caller may read by the formula, by name. */
  public List<DocumentTemplateView> list(CurrentUser caller) {
    settings.requireEnabled();
    Set<UUID> readable =
        accessService.readableAssetIds(
            DocumentTemplate.ASSET_TYPE, caller.id(), caller.organizationId());
    List<DocumentTemplate> templates =
        repository.findAllById(readable).stream()
            .sorted(
                Comparator.comparing(DocumentTemplate::getName)
                    .thenComparing(DocumentTemplate::getId))
            .toList();
    Map<UUID, AssetRole> roles =
        accessService.effectiveRoles(DocumentTemplate.ASSET_TYPE, readable, caller.id());
    Map<UUID, AssetReach> reach = accessService.reachByAsset(DocumentTemplate.ASSET_TYPE, readable);
    Map<UUID, String> names = ownerNames.of(templates);
    Map<UUID, SuccessionFinding> succession = successionSource.findingsAmong(templates, false);
    Map<UUID, AssetResponsibilityView> responsibilities =
        responsibilityService.viewsOf(templates, caller, roles);
    List<DocumentTemplateView> views = new ArrayList<>(templates.size());
    for (DocumentTemplate template : templates) {
      views.add(
          new DocumentTemplateView(
              template,
              Objects.requireNonNullElse(roles.get(template.getId()), AssetRole.VIEWER),
              names.get(template.getOwnerId()),
              succession.get(template.getId()),
              reach.getOrDefault(template.getId(), AssetReach.NONE),
              responsibilities.get(template.getId())));
    }
    return views;
  }

  public DocumentTemplateView get(UUID templateId, CurrentUser caller) {
    DocumentTemplate template = load(templateId, caller);
    AssetRole role =
        authorization.requireRole(template, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);
    return view(template, role, caller);
  }

  /** The file as uploaded, with its name ({@code VIEWER}). */
  public TemplateFile download(UUID templateId, CurrentUser caller) {
    DocumentTemplate template = load(templateId, caller);
    authorization.requireRole(template, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);
    byte[] content =
        files
            .load(template.getId())
            .orElseThrow(() -> new NotFoundException("Vorlage nicht gefunden"));
    return new TemplateFile(template.getFileName(), content);
  }

  /** Replaces name and description ({@code MANAGER}). */
  @Transactional
  public DocumentTemplateView update(
      UUID templateId, String name, String description, CurrentUser caller) {
    return update(templateId, name, description, null, caller);
  }

  /**
   * Replaces name, description and, unless {@code responsible} is {@code null}, the responsible
   * unit ({@code MANAGER}).
   */
  @Transactional
  public DocumentTemplateView update(
      UUID templateId,
      String name,
      String description,
      ResponsibleUnit responsible,
      CurrentUser caller) {
    DocumentTemplate template = load(templateId, caller);
    AssetRole role =
        authorization.requireRole(template, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    String validName = validateName(name);
    String validDescription = validateDescription(description);
    if (responsible != null) {
      responsibilityService.change(
          template, responsibilityService.requireValid(responsible, caller), caller);
    }
    List<String> changedFields = new ArrayList<>();
    if (!Objects.equals(template.getName(), validName)) {
      changedFields.add("name");
    }
    if (!Objects.equals(template.getDescription(), validDescription)) {
      changedFields.add("description");
    }
    if (!changedFields.isEmpty()) {
      template.rename(validName, validDescription);
      recordChanged(template, caller, changedFields);
    }
    return view(repository.save(template), role, caller);
  }

  /** Replaces the file ({@code EDITOR}, the template's content), admitted like on creation. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public DocumentTemplateView replaceFile(
      UUID templateId, DocumentTemplateUpload upload, CurrentUser caller) {
    DocumentTemplate before = load(templateId, caller);
    authorization.requireRole(before, caller.id(), caller.isSystemAdmin(), AssetRole.EDITOR);
    Admitted admitted = admit(upload, caller.organizationId());
    return writes.execute(
        status -> {
          DocumentTemplate template = load(templateId, caller);
          AssetRole role =
              authorization.requireRole(
                  template, caller.id(), caller.isSystemAdmin(), AssetRole.EDITOR);
          template.replaceFile(admitted.file());
          DocumentTemplate saved = repository.saveAndFlush(template);
          files.store(saved.getId(), admitted.content());
          recordChanged(saved, caller, List.of("file"));
          return view(saved, role, caller);
        });
  }

  /** Deletes the template ({@code OWNER}) with its file, grants and space associations. */
  @Transactional
  public void delete(UUID templateId, CurrentUser caller) {
    DocumentTemplate template = load(templateId, caller);
    authorization.requireRole(template, caller.id(), caller.isSystemAdmin(), AssetRole.OWNER);
    shellService.registerDeleted(template, caller.id());
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(template.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.DOCUMENT_TEMPLATE_DELETED)
            .object(AuditObjectType.DOCUMENT_TEMPLATE, template.getId(), template.auditName())
            .before(template.auditPayload(auditPayload(template)))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    repository.delete(template);
  }

  /**
   * Locks the template (system administration only) with a required reason: from the next run on,
   * on every instance, no agent is offered it. It stays manageable and is never deleted.
   */
  @Transactional
  public AssetResponsibilityView lock(UUID templateId, String reason, CurrentUser caller) {
    caller.requireSystemAdmin("Nur die Systemverwaltung darf Vorlagen sperren.");
    return responsibilityService.lock(load(templateId, caller), reason, caller);
  }

  /** Lifts the lock of the template (system administration only), whatever its cause. */
  @Transactional
  public AssetResponsibilityView unlock(UUID templateId, CurrentUser caller) {
    caller.requireSystemAdmin("Nur die Systemverwaltung darf Vorlagen entsperren.");
    return responsibilityService.unlock(load(templateId, caller), caller);
  }

  /**
   * The template of the caller's organization with this id; an unknown id, one of another
   * organization and any id while the switch is off answer the same {@code 404}.
   */
  DocumentTemplate load(UUID templateId, CurrentUser caller) {
    settings.requireEnabled();
    Asset asset =
        authorization.load(DocumentTemplate.ASSET_TYPE, templateId, caller.organizationId());
    if (Hibernate.unproxy(asset) instanceof DocumentTemplate template) {
      return template;
    }
    throw new NotFoundException("Vorlage nicht gefunden");
  }

  /** A template file as stored. */
  public record TemplateFile(String fileName, byte[] content) {}

  private record Admitted(DocumentTemplate.StoredFile file, byte[] content) {}

  private Admitted admit(DocumentTemplateUpload upload, UUID organizationId) {
    if (upload == null) {
      throw new ValidationException("Bitte eine Word-Datei (.docx) auswählen.");
    }
    String fileName = validateFileName(upload.fileName());
    if (upload.sizeBytes() > DocxTemplateInspector.MAX_BYTES) {
      throw new PayloadTooLargeException("Die Vorlage ist größer als 10 MB.");
    }
    Path file = null;
    try {
      file = Files.createTempFile("opaa-template-", ".docx");
      try (InputStream in = upload.open()) {
        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
      }
      long size = Files.size(file);
      List<TemplatePlaceholder> placeholders = inspector.inspect(file, fileName, size);
      requireNoMalware(file, organizationId);
      byte[] content = Files.readAllBytes(file);
      return new Admitted(
          new DocumentTemplate.StoredFile(fileName, size, sha256(content), placeholders), content);
    } catch (IOException e) {
      throw new UncheckedIOException("Template upload could not be read", e);
    } finally {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
          // a leftover temp file is removed by the OS; the upload itself has its answer
        }
      }
    }
  }

  private void requireNoMalware(Path file, UUID organizationId) {
    switch (malwareScanService.checkTemplate(file, organizationId)) {
      case MalwareScanService.Verdict.Clean clean -> {}
      case MalwareScanService.Verdict.AcceptedUnscanned accepted -> {}
      case MalwareScanService.Verdict.Infected infected ->
          throw new ValidationException(
              MalwareScanService.INFECTED_MESSAGE + " (" + infected.signature() + ")");
      case MalwareScanService.Verdict.Unscanned unscanned ->
          throw new ServiceUnavailableException(MalwareScanService.UNSCANNED_MESSAGE);
    }
  }

  private void recordChanged(DocumentTemplate template, CurrentUser caller, List<String> fields) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(template.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.DOCUMENT_TEMPLATE_CHANGED)
            .object(AuditObjectType.DOCUMENT_TEMPLATE, template.getId(), template.auditName())
            .before(template.auditPayload(Map.of("changedFields", fields)))
            .after(template.auditPayload(Map.of("changedFields", fields)))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private DocumentTemplateView view(DocumentTemplate template, AssetRole role, CurrentUser caller) {
    return new DocumentTemplateView(
        template,
        role,
        ownerNames.of(List.of(template)).get(template.getOwnerId()),
        successionSource.findingsAmong(List.of(template), false).get(template.getId()),
        accessService
            .reachByAsset(DocumentTemplate.ASSET_TYPE, Set.of(template.getId()))
            .get(template.getId()),
        responsibilityService.view(template, caller, role));
  }

  private static Map<String, Object> auditPayload(DocumentTemplate template) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", template.getName());
    return payload;
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every JVM", e);
    }
  }

  private static String validateFileName(String fileName) {
    String normalized = fileName == null ? "" : fileName.strip();
    int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
    normalized = normalized.substring(slash + 1);
    if (normalized.chars().anyMatch(Character::isISOControl)) {
      throw new ValidationException("Der Dateiname enthält Steuerzeichen.");
    }
    if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("Der Dateiname fehlt oder ist zu lang.");
    }
    return normalized;
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
    return description == null || description.isBlank() ? null : description;
  }
}
