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
import io.opaa.asset.AssetShellService;
import io.opaa.asset.AssetSuccessionSource;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * run reads only what the calling person may read (ADR-0070, Entscheidung 3).
 */
@Service
@Transactional(readOnly = true)
public class AgentService {

  static final int MAX_NAME_LENGTH = 255;
  static final int MAX_DESCRIPTION_LENGTH = 2000;
  static final int MAX_INSTRUCTION_LENGTH = 8000;
  static final int MAX_LIBRARIES = 100;

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
  private final AuditEventRecorder auditEventRecorder;

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
      AuditEventRecorder auditEventRecorder) {
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
            "", Set.of(), AgentTools.DEFAULT, AgentPlanStep.OFF, AgentLimits.INSTALLATION));
    Agent saved = agentRepository.save(agent);
    shellService.registerCreated(saved, caller.id(), auditPayload(saved));
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
    LibraryNames libraryNames = libraryNames(agents, caller);
    List<AgentView> views = new ArrayList<>(agents.size());
    for (Agent agent : agents) {
      views.add(
          new AgentView(
              agent,
              Objects.requireNonNullElse(roles.get(agent.getId()), AssetRole.VIEWER),
              libraryNames.visible(agent),
              libraryNames.hiddenCount(agent),
              names.get(agent.getOwnerId()),
              succession.get(agent.getId()),
              reach.getOrDefault(agent.getId(), AssetReach.NONE)));
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
   * Replaces name, purpose and configuration ({@code MANAGER}). Each change counts the revision up
   * and writes {@code AGENT_CHANGED} with the names of the changed fields, never their values.
   */
  @Transactional
  public AgentView update(UUID agentId, AgentUpdate request, CurrentUser caller) {
    requireEnabled();
    Agent agent = load(agentId, caller);
    AssetRole role =
        authorization.requireRole(agent, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    AgentConfiguration submitted =
        request.configuration() == null ? agent.configuration() : request.configuration();
    Set<UUID> readable = libraryAccess.readableLibraryIds(caller.id(), caller.organizationId());

    List<FieldError> errors = new ArrayList<>();
    String name = validateName(request.name(), errors);
    String description = validateDescription(request.description(), errors);
    String instruction = validateInstruction(submitted.instruction(), errors);
    Set<UUID> libraryIds =
        validateLibraries(submitted.libraryIds(), agent.getLibraryIds(), readable, errors);
    Set<String> toolNames = validateTools(submitted.toolNames(), errors);
    AgentLimits limits = validateLimits(submitted.limits(), errors);
    throwIfAny(errors);

    AgentConfiguration target =
        new AgentConfiguration(instruction, libraryIds, toolNames, submitted.planStep(), limits);
    List<String> changedFields = changedFields(agent, name, description, target);
    if (!changedFields.isEmpty()) {
      if (!Objects.equals(agent.getName(), name)
          || !Objects.equals(agent.getDescription(), description)) {
        agent.rename(name, description);
      }
      agent.configure(target);
      agent.countRevision();
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
    LibraryNames libraryNames = libraryNames(List.of(agent), caller);
    return new AgentView(
        agent,
        role,
        libraryNames.visible(agent),
        libraryNames.hiddenCount(agent),
        ownerNames.of(List.of(agent)).get(agent.getOwnerId()),
        successionSource.findingsAmong(List.of(agent), false).get(agent.getId()),
        accessService
            .reachByAsset(Agent.ASSET_TYPE, Set.of(agent.getId()))
            .getOrDefault(agent.getId(), AssetReach.NONE));
  }

  /** The names of the assigned libraries the caller may read, in one query for all agents. */
  private LibraryNames libraryNames(List<Agent> agents, CurrentUser caller) {
    Set<UUID> assigned = new HashSet<>();
    agents.forEach(agent -> assigned.addAll(agent.getLibraryIds()));
    if (assigned.isEmpty()) {
      return new LibraryNames(Map.of());
    }
    Set<UUID> readable = libraryAccess.readableLibraryIds(caller.id(), caller.organizationId());
    assigned.retainAll(readable);
    Map<UUID, String> names = new LinkedHashMap<>();
    for (KnowledgeLibrary library : libraryRepository.findAllById(assigned)) {
      if (library.getOrganizationId().equals(caller.organizationId())) {
        names.put(library.getId(), library.getName());
      }
    }
    return new LibraryNames(names);
  }

  private record LibraryNames(Map<UUID, String> readableNames) {

    List<AgentView.LibraryRef> visible(Agent agent) {
      return agent.getLibraryIds().stream()
          .filter(readableNames::containsKey)
          .map(id -> new AgentView.LibraryRef(id, readableNames.get(id)))
          .sorted(
              Comparator.comparing(AgentView.LibraryRef::name)
                  .thenComparing(AgentView.LibraryRef::id))
          .toList();
    }

    int hiddenCount(Agent agent) {
      return (int)
          agent.getLibraryIds().stream().filter(id -> !readableNames.containsKey(id)).count();
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
   * them. A submitted library the editor may not read and that was not assigned before is refused,
   * like an unknown one.
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
    requirePositive(limits.maxToolCalls(), "limits.maxToolCalls", errors);
    requirePositive(limits.maxSearchCalls(), "limits.maxSearchCalls", errors);
    requirePositive(limits.maxDurationSeconds(), "limits.maxDurationSeconds", errors);
    requirePositive(limits.maxCollectedChars(), "limits.maxCollectedChars", errors);
    return limits;
  }

  private static void requirePositive(Integer value, String field, List<FieldError> errors) {
    if (value != null && value < 1) {
      errors.add(new FieldError(field, "OUT_OF_RANGE", "Der Wert muss mindestens 1 sein."));
    }
  }
}
