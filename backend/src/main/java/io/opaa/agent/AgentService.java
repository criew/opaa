package io.opaa.agent;

import io.opaa.api.types.AgentPlanStep;
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
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.NotFoundException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetReach;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.SuccessionFinding;
import io.opaa.template.DocumentTemplate;
import io.opaa.template.DocumentTemplateRepository;
import io.opaa.template.DocumentTemplateSettingsService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create, read, change and delete agents. Every rights question goes to the asset shell, as for
 * prompt libraries: the list is the formula's ({@link AssetAccessService#readableAssetIds}), a
 * single agent the administration's ({@link AssetAuthorization#requireRole}) - {@code VIEWER} to
 * read, {@code MANAGER} to change, {@code OWNER} to delete.
 *
 * <p>While agents are switched off ({@link AgentSwitch}), every path answers like an unknown
 * address. A library is assigned only if the editor may read it; an assigned library the editor may
 * not read stays assigned and is shown only as a count, never by name. Assigning grants nothing: a
 * run reads only what the calling person may read (ADR-0070, Entscheidung 3). Document templates
 * follow the same rule; a run fills only those the asker may read as well. While the switch
 * "Vorlagen" is off, a change keeps the stored templates and no view names or counts one.
 *
 * <p>Every agent names a responsible unit ({@link AssetResponsibilityService}); the system
 * administration may lock a single agent. A locked agent stays manageable, is left out of the list
 * to choose from and refused for every run ({@link #requireUsableForRun}).
 */
@Service
@Transactional(readOnly = true)
public class AgentService {

  static final int MAX_NAME_LENGTH = 255;
  static final int MAX_DESCRIPTION_LENGTH = 2000;
  static final int MAX_INSTRUCTION_LENGTH = 8000;
  static final int MAX_LIBRARIES = 100;
  static final int MAX_TEMPLATES = 20;

  /** The code a run of a locked agent is refused with. */
  public static final String LOCKED_CODE = "AGENT_LOCKED";

  private final AgentRepository agentRepository;
  private final AgentSwitch agentSwitch;
  private final CapabilityService capabilityService;
  private final AssetAuthorization authorization;
  private final AssetAccessService accessService;
  private final AssetGrantService grantService;
  private final AssetShellService shellService;
  private final AssetOwnerNames ownerNames;
  private final AssetSuccessionSource successionSource;
  private final LibraryAccessService libraryAccess;
  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentTemplateRepository templateRepository;
  private final DocumentTemplateSettingsService templateSettings;
  private final AuditEventRecorder auditEventRecorder;
  private final AssetResponsibilityService responsibilityService;

  AgentService(
      AgentRepository agentRepository,
      AgentSwitch agentSwitch,
      CapabilityService capabilityService,
      AssetAuthorization authorization,
      AssetAccessService accessService,
      AssetGrantService grantService,
      AssetShellService shellService,
      AssetOwnerNames ownerNames,
      AssetSuccessionSource successionSource,
      LibraryAccessService libraryAccess,
      KnowledgeLibraryRepository libraryRepository,
      DocumentTemplateRepository templateRepository,
      DocumentTemplateSettingsService templateSettings,
      AuditEventRecorder auditEventRecorder,
      AssetResponsibilityService responsibilityService) {
    this.responsibilityService = responsibilityService;
    this.agentRepository = agentRepository;
    this.agentSwitch = agentSwitch;
    this.capabilityService = capabilityService;
    this.authorization = authorization;
    this.accessService = accessService;
    this.grantService = grantService;
    this.shellService = shellService;
    this.ownerNames = ownerNames;
    this.successionSource = successionSource;
    this.libraryAccess = libraryAccess;
    this.libraryRepository = libraryRepository;
    this.templateRepository = templateRepository;
    this.templateSettings = templateSettings;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * Creates an empty agent owned by the caller or, for {@link AssetOwnerType#GROUP}, by one of the
   * caller's groups: no instruction, no library (the caller's whole readable scope), searching and
   * reading as tools, no plan step, the installation's limits. Needs {@link
   * Capability#CREATE_AGENT}.
   */
  @Transactional
  public AgentView create(AgentCreation request, CurrentUser caller) {
    requireEnabled();
    capabilityService.requireCapability(caller, Capability.CREATE_AGENT);
    List<FieldError> errors = new ArrayList<>();
    String name = validateName(request.name(), errors);
    String description = validateDescription(request.description(), errors);
    ResponsibleUnit responsible =
        responsibilityService.validate(request.responsible(), caller, errors);
    throwIfAny(errors);

    Agent agent;
    if (request.ownerType() == AssetOwnerType.GROUP) {
      if (request.ownerId() == null) {
        throw new FieldValidationException(
            "Die Angaben sind unvollständig.",
            List.of(
                new FieldError(
                    "ownerId", "REQUIRED", "Wählen Sie die Gruppe, der der Agent gehören soll.")));
      }
      grantService.requireOwnableGroup(request.ownerId(), Agent.ASSET_TYPE, caller);
      agent = Agent.ownedByGroup(caller.organizationId(), name, description, request.ownerId());
    } else {
      agent = Agent.ownedByUser(caller.organizationId(), name, description, caller.id());
    }
    agent.configure(
        new AgentConfiguration(
            "",
            Set.of(),
            AgentTools.DEFAULT,
            AgentPlanStep.OFF,
            AgentLimits.INSTALLATION,
            Set.of()));
    Agent saved = agentRepository.save(agent);
    shellService.registerCreated(saved, caller.id(), auditPayload(saved));
    responsibilityService.establish(saved, responsible);
    return view(saved, AssetRole.OWNER, caller);
  }

  /**
   * Every agent the caller may read by the formula, by name. {@code myRole} is the formula's, at
   * least {@code VIEWER}.
   */
  public List<AgentView> list(CurrentUser caller) {
    requireEnabled();
    Set<UUID> readable =
        accessService.readableAssetIds(Agent.ASSET_TYPE, caller.id(), caller.organizationId());
    List<Agent> agents =
        agentRepository.findAllById(readable).stream()
            .sorted(Comparator.comparing(Agent::getName).thenComparing(Agent::getId))
            .toList();
    Map<UUID, AssetRole> roles =
        accessService.effectiveRoles(Agent.ASSET_TYPE, readable, caller.id());
    Map<UUID, AssetReach> reach = accessService.reachByAsset(Agent.ASSET_TYPE, readable);
    Map<UUID, String> names = ownerNames.of(agents);
    Map<UUID, SuccessionFinding> succession = successionSource.findingsAmong(agents, false);
    AssignedNames libraryNames = libraryNames(agents, caller);
    AssignedNames templateNames = templateNames(agents, caller);
    Map<UUID, AssetResponsibilityView> responsibilities =
        responsibilityService.viewsOf(agents, caller, roles);
    List<AgentView> views = new ArrayList<>(agents.size());
    for (Agent agent : agents) {
      views.add(
          new AgentView(
              agent,
              Objects.requireNonNullElse(roles.get(agent.getId()), AssetRole.VIEWER),
              libraries(libraryNames, agent),
              libraryNames.hiddenCount(agent.getLibraryIds()),
              names.get(agent.getOwnerId()),
              succession.get(agent.getId()),
              reach.getOrDefault(agent.getId(), AssetReach.NONE),
              true,
              templates(templateNames, agent),
              templateNames.hiddenCount(agent.getTemplateIds()),
              responsibilities.get(agent.getId())));
    }
    return views;
  }

  public AgentView get(UUID agentId, CurrentUser caller) {
    requireEnabled();
    Agent agent = load(agentId, caller);
    AssetRole role =
        authorization.requireRole(agent, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);
    return view(agent, role, caller);
  }

  /**
   * Replaces name, purpose and configuration ({@code MANAGER}). A change of what a run uses counts
   * the revision up, a new name or purpose alone does not; each change writes {@code AGENT_CHANGED}
   * with the names of the changed fields, never their values. A caller who reaches the agent only
   * through the system administration keeps the instruction.
   */
  @Transactional
  public AgentView update(UUID agentId, AgentUpdate request, CurrentUser caller) {
    requireEnabled();
    agentRepository.lockById(agentId);
    Agent agent = load(agentId, caller);
    AssetRole role =
        authorization.requireRole(agent, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    AgentConfiguration submitted =
        request.configuration() == null ? agent.configuration() : request.configuration();
    Set<UUID> readable = libraryAccess.readableLibraryIds(caller.id(), caller.organizationId());

    List<FieldError> errors = new ArrayList<>();
    String name = validateName(request.name(), errors);
    String description = validateDescription(request.description(), errors);
    String instruction =
        holdsContentRole(agent, caller, AssetRole.MANAGER)
            ? validateInstruction(submitted.instruction(), errors)
            : agent.configuration().instruction();
    Set<UUID> libraryIds =
        validateLibraries(
            submitted.libraryIds(),
            existingLibraryIds(agent.getLibraryIds(), agent.getOrganizationId()),
            readable,
            errors);
    Set<String> toolNames = validateTools(submitted.toolNames(), errors);
    AgentLimits limits = validateLimits(submitted.limits(), errors);
    ResponsibleUnit responsible =
        request.responsible() == null
            ? null
            : responsibilityService.validate(request.responsible(), caller, errors);
    Set<UUID> templateIds =
        submitted.templateIds() == null || !templateSettings.isEnabled()
            ? agent.getTemplateIds()
            : validateTemplates(
                submitted.templateIds(),
                existingTemplateIds(agent.getTemplateIds(), agent.getOrganizationId()),
                accessService.readableAssetIds(
                    DocumentTemplate.ASSET_TYPE, caller.id(), caller.organizationId()),
                errors);
    throwIfAny(errors);
    if (responsible != null) {
      responsibilityService.change(agent, responsible, caller);
    }

    AgentConfiguration target =
        new AgentConfiguration(
            instruction, libraryIds, toolNames, submitted.planStep(), limits, templateIds);
    List<String> changedFields = changedFields(agent, name, description, target);
    if (!changedFields.isEmpty()) {
      if (!Objects.equals(agent.getName(), name)
          || !Objects.equals(agent.getDescription(), description)) {
        agent.rename(name, description);
      }
      if (!agent.configuration().equals(target)) {
        agent.configure(target);
        agent.countRevision();
      }
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(agent.getOrganizationId())
              .actor(caller.id())
              .type(AuditEventType.AGENT_CHANGED)
              .object(AuditObjectType.AGENT, agent.getId(), agent.auditName())
              .before(agent.auditPayload(Map.of("changedFields", changedFields)))
              .after(agent.auditPayload(Map.of("changedFields", changedFields)))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return view(agentRepository.save(agent), role, caller);
  }

  /**
   * Deletes the agent ({@code OWNER}); grants and space associations go with the shell row, the
   * open history intervals are closed first.
   */
  @Transactional
  public void delete(UUID agentId, CurrentUser caller) {
    requireEnabled();
    agentRepository.lockById(agentId);
    Agent agent = load(agentId, caller);
    authorization.requireRole(agent, caller.id(), caller.isSystemAdmin(), AssetRole.OWNER);
    shellService.registerDeleted(agent, caller.id());
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(agent.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.AGENT_DELETED)
            .object(AuditObjectType.AGENT, agent.getId(), agent.auditName())
            .before(agent.auditPayload(auditPayload(agent)))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    agentRepository.delete(agent);
  }

  /**
   * Locks the agent (system administration only) with a required reason; it stays manageable and is
   * never deleted. Holds for the next run on every instance.
   */
  @Transactional
  public AssetResponsibilityView lock(UUID agentId, String reason, CurrentUser caller) {
    requireEnabled();
    caller.requireSystemAdmin("Nur die Systemverwaltung darf Agenten sperren.");
    agentRepository.lockById(agentId);
    return responsibilityService.lock(load(agentId, caller), reason, caller);
  }

  /** Lifts the lock of the agent (system administration only), whatever its cause. */
  @Transactional
  public AssetResponsibilityView unlock(UUID agentId, CurrentUser caller) {
    requireEnabled();
    caller.requireSystemAdmin("Nur die Systemverwaltung darf Agenten entsperren.");
    agentRepository.lockById(agentId);
    return responsibilityService.unlock(load(agentId, caller), caller);
  }

  /**
   * The gate every run of an agent passes, after the caller's right to see it was checked: a locked
   * agent is refused with {@code 409} and {@link #LOCKED_CODE}, read from the table on every call
   * so a lock holds on every instance from the next run on (ADR-0053). Using an agent writes no
   * audit entry.
   */
  public void requireUsableForRun(Agent agent) {
    if (responsibilityService.isLocked(agent.getId())) {
      throw new ConflictException(
          "Der Agent „"
              + agent.getName()
              + "“ ist gesperrt und kann gerade nicht genutzt werden. Wenden Sie sich an die"
              + " Systemverwaltung.",
          LOCKED_CODE);
    }
  }

  /** The {@code 404} of a switched-off installation: agents cannot be told from no agents. */
  private void requireEnabled() {
    if (!agentSwitch.isEnabled()) {
      throw new NotFoundException("Agent nicht gefunden");
    }
  }

  /**
   * The agent of the caller's organization with this id; an unknown id and one of another
   * organization answer the same {@code 404}.
   */
  Agent load(UUID agentId, CurrentUser caller) {
    Asset asset = authorization.load(Agent.ASSET_TYPE, agentId, caller.organizationId());
    if (Hibernate.unproxy(asset) instanceof Agent agent) {
      return agent;
    }
    throw new NotFoundException("Agent nicht gefunden");
  }

  private AgentView view(Agent agent, AssetRole role, CurrentUser caller) {
    AssignedNames libraryNames = libraryNames(List.of(agent), caller);
    AssignedNames templateNames = templateNames(List.of(agent), caller);
    return new AgentView(
        agent,
        role,
        libraries(libraryNames, agent),
        libraryNames.hiddenCount(agent.getLibraryIds()),
        ownerNames.of(List.of(agent)).get(agent.getOwnerId()),
        successionSource.findingsAmong(List.of(agent), false).get(agent.getId()),
        accessService
            .reachByAsset(Agent.ASSET_TYPE, Set.of(agent.getId()))
            .getOrDefault(agent.getId(), AssetReach.NONE),
        holdsContentRole(agent, caller, AssetRole.VIEWER),
        templates(templateNames, agent),
        templateNames.hiddenCount(agent.getTemplateIds()),
        responsibilityService.view(agent, caller, role));
  }

  /**
   * Whether the caller holds at least {@code required} on the agent by the formula, not only
   * through the system administration; reading the instruction needs {@code VIEWER}, changing it
   * {@code MANAGER}.
   */
  private boolean holdsContentRole(Agent agent, CurrentUser caller, AssetRole required) {
    if (!caller.isSystemAdmin()) {
      return true;
    }
    AssetRole role =
        accessService
            .effectiveRoles(Agent.ASSET_TYPE, Set.of(agent.getId()), caller.id())
            .get(agent.getId());
    return role != null && role.atLeast(required);
  }

  /** The ids among these that still name a library of the organization; a deleted one drops out. */
  private Set<UUID> existingLibraryIds(Set<UUID> ids, UUID organizationId) {
    Set<UUID> existing = new HashSet<>();
    if (ids.isEmpty()) {
      return existing;
    }
    for (KnowledgeLibrary library : libraryRepository.findAllById(ids)) {
      if (library.getOrganizationId().equals(organizationId)) {
        existing.add(library.getId());
      }
    }
    return existing;
  }

  /**
   * The ids among these that still name a template of the organization; a deleted one drops out.
   */
  private Set<UUID> existingTemplateIds(Set<UUID> ids, UUID organizationId) {
    Set<UUID> existing = new HashSet<>();
    if (ids.isEmpty()) {
      return existing;
    }
    for (DocumentTemplate template : templateRepository.findAllById(ids)) {
      if (template.getOrganizationId().equals(organizationId)) {
        existing.add(template.getId());
      }
    }
    return existing;
  }

  /**
   * The names of the assigned libraries the caller may read and the ids of all that still exist, in
   * one query for all agents.
   */
  private AssignedNames libraryNames(List<Agent> agents, CurrentUser caller) {
    Set<UUID> assigned = new HashSet<>();
    agents.forEach(agent -> assigned.addAll(agent.getLibraryIds()));
    if (assigned.isEmpty()) {
      return new AssignedNames(Map.of(), Set.of());
    }
    Set<UUID> readable = libraryAccess.readableLibraryIds(caller.id(), caller.organizationId());
    Map<UUID, String> names = new LinkedHashMap<>();
    Set<UUID> existing = new HashSet<>();
    for (KnowledgeLibrary library : libraryRepository.findAllById(assigned)) {
      if (library.getOrganizationId().equals(caller.organizationId())) {
        boolean readableByCaller = readable.contains(library.getId());
        // Somebody else's private library does not exist for the caller: neither named nor
        // counted as hidden.
        if (readableByCaller || !library.isOwnerOnly()) {
          existing.add(library.getId());
        }
        if (readableByCaller) {
          names.put(library.getId(), library.getName());
        }
      }
    }
    return new AssignedNames(names, existing);
  }

  /** As {@link #libraryNames}, for the document templates by the formula of the asset shell. */
  private AssignedNames templateNames(List<Agent> agents, CurrentUser caller) {
    Set<UUID> assigned = new HashSet<>();
    agents.forEach(agent -> assigned.addAll(agent.getTemplateIds()));
    if (assigned.isEmpty() || !templateSettings.isEnabled()) {
      return new AssignedNames(Map.of(), Set.of());
    }
    Set<UUID> readable =
        accessService.readableAssetIds(
            DocumentTemplate.ASSET_TYPE, caller.id(), caller.organizationId());
    Map<UUID, String> names = new LinkedHashMap<>();
    Set<UUID> existing = new HashSet<>();
    for (DocumentTemplate template : templateRepository.findAllById(assigned)) {
      if (template.getOrganizationId().equals(caller.organizationId())) {
        existing.add(template.getId());
        if (readable.contains(template.getId())) {
          names.put(template.getId(), template.getName());
        }
      }
    }
    return new AssignedNames(names, existing);
  }

  private static List<AgentView.LibraryRef> libraries(AssignedNames names, Agent agent) {
    return names.visible(agent.getLibraryIds()).stream()
        .map(id -> new AgentView.LibraryRef(id, names.readableNames().get(id)))
        .toList();
  }

  private static List<AgentView.TemplateRef> templates(AssignedNames names, Agent agent) {
    return names.visible(agent.getTemplateIds()).stream()
        .map(id -> new AgentView.TemplateRef(id, names.readableNames().get(id)))
        .toList();
  }

  /** A deleted object counts as neither visible nor hidden; the next change drops it. */
  private record AssignedNames(Map<UUID, String> readableNames, Set<UUID> existing) {

    /** The readable ids among {@code assigned}, by name. */
    List<UUID> visible(Set<UUID> assigned) {
      return assigned.stream()
          .filter(readableNames::containsKey)
          .sorted(Comparator.comparing((UUID id) -> readableNames.get(id)).thenComparing(id -> id))
          .toList();
    }

    int hiddenCount(Set<UUID> assigned) {
      return (int)
          assigned.stream()
              .filter(id -> existing.contains(id) && !readableNames.containsKey(id))
              .count();
    }
  }

  private static List<String> changedFields(
      Agent agent, String name, String description, AgentConfiguration target) {
    AgentConfiguration current = agent.configuration();
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(agent.getName(), name)) {
      changed.add("name");
    }
    if (!Objects.equals(agent.getDescription(), description)) {
      changed.add("description");
    }
    if (!current.instruction().equals(target.instruction())) {
      changed.add("instruction");
    }
    if (!current.libraryIds().equals(target.libraryIds())) {
      changed.add("libraries");
    }
    if (!current.toolNames().equals(target.toolNames())) {
      changed.add("tools");
    }
    if (current.planStep() != target.planStep()) {
      changed.add("planStep");
    }
    if (!current.limits().equals(target.limits())) {
      changed.add("limits");
    }
    if (!current.templateIds().equals(target.templateIds())) {
      changed.add("templates");
    }
    return changed;
  }

  private static Map<String, Object> auditPayload(Agent agent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", agent.getName());
    return payload;
  }

  private static void throwIfAny(List<FieldError> errors) {
    if (!errors.isEmpty()) {
      throw new FieldValidationException("Bitte prüfen Sie die markierten Angaben.", errors);
    }
  }

  private static String validateName(String name, List<FieldError> errors) {
    String normalized = name == null ? "" : name.strip();
    if (normalized.isEmpty()) {
      errors.add(new FieldError("name", "REQUIRED", "Geben Sie dem Agenten einen Namen."));
    } else if (normalized.length() > MAX_NAME_LENGTH) {
      errors.add(
          new FieldError(
              "name", "TOO_LONG", "Der Name hat höchstens " + MAX_NAME_LENGTH + " Zeichen."));
    }
    return normalized;
  }

  private static String validateDescription(String description, List<FieldError> errors) {
    String normalized = description == null ? null : description.strip();
    if (normalized != null && normalized.isEmpty()) {
      return null;
    }
    if (normalized != null && normalized.length() > MAX_DESCRIPTION_LENGTH) {
      errors.add(
          new FieldError(
              "description",
              "TOO_LONG",
              "Der Zweck hat höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen."));
    }
    return normalized;
  }

  private static String validateInstruction(String instruction, List<FieldError> errors) {
    String normalized = instruction == null ? "" : instruction.strip();
    if (normalized.length() > MAX_INSTRUCTION_LENGTH) {
      errors.add(
          new FieldError(
              "instruction",
              "TOO_LONG",
              "Die Anweisung hat höchstens " + MAX_INSTRUCTION_LENGTH + " Zeichen."));
    }
    return normalized;
  }

  /**
   * The libraries to store: those submitted that the editor may read, plus those already assigned
   * that the editor may not read - an editor cannot see them and so cannot have meant to remove
   * them, as long as they still exist. A submitted library the editor may not read and that was not
   * assigned before is refused, like an unknown one.
   */
  private static Set<UUID> validateLibraries(
      Set<UUID> submitted, Set<UUID> assigned, Set<UUID> readable, List<FieldError> errors) {
    Set<UUID> result = new HashSet<>();
    for (UUID id : submitted) {
      if (readable.contains(id) || assigned.contains(id)) {
        result.add(id);
      } else {
        errors.add(
            new FieldError(
                "libraryIds",
                "UNKNOWN",
                "Eine gewählte Bibliothek gibt es nicht oder Sie dürfen sie nicht lesen."));
        return result;
      }
    }
    for (UUID id : assigned) {
      if (!readable.contains(id)) {
        result.add(id);
      }
    }
    if (result.size() > MAX_LIBRARIES) {
      errors.add(
          new FieldError(
              "libraryIds",
              "TOO_MANY",
              "Ein Agent nutzt höchstens " + MAX_LIBRARIES + " Bibliotheken."));
    }
    return result;
  }

  /**
   * The templates to store, by the rule of {@link #validateLibraries}: a submitted template the
   * editor may not read and that was not assigned before is refused; an assigned one the editor may
   * not read stays.
   */
  private static Set<UUID> validateTemplates(
      Set<UUID> submitted, Set<UUID> assigned, Set<UUID> readable, List<FieldError> errors) {
    Set<UUID> result = new HashSet<>();
    for (UUID id : submitted) {
      if (readable.contains(id) || assigned.contains(id)) {
        result.add(id);
      } else {
        errors.add(
            new FieldError(
                "templateIds",
                "UNKNOWN",
                "Eine gewählte Vorlage gibt es nicht oder Sie dürfen sie nicht lesen."));
        return result;
      }
    }
    for (UUID id : assigned) {
      if (!readable.contains(id)) {
        result.add(id);
      }
    }
    if (result.size() > MAX_TEMPLATES) {
      errors.add(
          new FieldError(
              "templateIds",
              "TOO_MANY",
              "Ein Agent nutzt höchstens " + MAX_TEMPLATES + " Vorlagen."));
    }
    return result;
  }

  private static Set<String> validateTools(Set<String> toolNames, List<FieldError> errors) {
    for (String tool : toolNames) {
      if (!AgentTools.ASSIGNABLE.contains(tool)) {
        errors.add(new FieldError("toolNames", "UNKNOWN", "Unbekanntes Werkzeug: " + tool + "."));
        return Set.of();
      }
    }
    return toolNames;
  }

  private static AgentLimits validateLimits(AgentLimits limits, List<FieldError> errors) {
    requireInRange(limits.maxToolCalls(), 1_000, "limits.maxToolCalls", errors);
    requireInRange(limits.maxSearchCalls(), 1_000, "limits.maxSearchCalls", errors);
    requireInRange(limits.maxDurationSeconds(), 86_400, "limits.maxDurationSeconds", errors);
    requireInRange(limits.maxCollectedChars(), 10_000_000, "limits.maxCollectedChars", errors);
    return limits;
  }

  private static void requireInRange(
      Integer value, int maximum, String field, List<FieldError> errors) {
    if (value != null && (value < 1 || value > maximum)) {
      errors.add(
          new FieldError(
              field,
              "OUT_OF_RANGE",
              "Der Wert muss zwischen 1 und "
                  + String.format(Locale.GERMANY, "%,d", maximum)
                  + " liegen."));
    }
  }
}
