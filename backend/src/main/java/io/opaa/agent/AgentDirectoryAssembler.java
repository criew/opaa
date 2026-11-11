package io.opaa.agent;

import io.opaa.agent.AgentDirectory.AgentEntry;
import io.opaa.agent.AgentDirectory.Responsibility;
import io.opaa.agent.AgentDirectory.TemplateEntry;
import io.opaa.asset.AssetResponsibilityService;
import io.opaa.asset.AssetResponsibilityView;
import io.opaa.auth.CurrentUser;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.template.DocumentTemplate;
import io.opaa.template.DocumentTemplateRepository;
import io.opaa.template.DocumentTemplateSettingsService;
import io.opaa.template.TemplatePlaceholder;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the agent directory of the caller's organization, independent of the caller's rights on the
 * single objects; the right to the directory is {@link AgentDirectoryService}'s to check. A library
 * is read for its name only, never for its content, and a private library not at all: it does not
 * exist for anyone but its owner (ADR-0041), so it drops out of the list of an agent like a deleted
 * one, without a count and without a hint, while the agent still uses it for its owner. A lock is
 * read only for since when - its reason stays with the system administration and the managers of
 * the object.
 */
@Component
class AgentDirectoryAssembler {

  private static final Comparator<String> BY_NAME = Comparator.comparing(String::toLowerCase);

  private final AgentRepository agentRepository;
  private final DocumentTemplateRepository templateRepository;
  private final DocumentTemplateSettingsService templateSettings;
  private final KnowledgeLibraryRepository libraryRepository;
  private final AssetResponsibilityService responsibilityService;

  AgentDirectoryAssembler(
      AgentRepository agentRepository,
      DocumentTemplateRepository templateRepository,
      DocumentTemplateSettingsService templateSettings,
      KnowledgeLibraryRepository libraryRepository,
      AssetResponsibilityService responsibilityService) {
    this.agentRepository = agentRepository;
    this.templateRepository = templateRepository;
    this.templateSettings = templateSettings;
    this.libraryRepository = libraryRepository;
    this.responsibilityService = responsibilityService;
  }

  @Transactional(readOnly = true)
  public AgentDirectory assemble(CurrentUser caller) {
    UUID organizationId = caller.organizationId();
    List<Agent> agents =
        agentRepository.findByOrganizationId(organizationId).stream()
            .sorted(Comparator.comparing(Agent::getName, BY_NAME).thenComparing(Agent::getId))
            .toList();
    boolean templatesIncluded = templateSettings.isEnabled();
    List<DocumentTemplate> templates =
        templatesIncluded
            ? templateRepository.findByOrganizationId(organizationId).stream()
                .sorted(
                    Comparator.comparing(DocumentTemplate::getName, BY_NAME)
                        .thenComparing(DocumentTemplate::getId))
                .toList()
            : List.of();

    Map<UUID, String> libraryNames = libraryNames(agents, organizationId);
    Map<UUID, String> templateNames = new HashMap<>();
    templates.forEach(template -> templateNames.put(template.getId(), template.getName()));
    // No role on any object: the reader of the directory holds none by it.
    Map<UUID, AssetResponsibilityView> agentResponsibilities =
        responsibilityService.viewsOf(agents, caller, Map.of());
    Map<UUID, AssetResponsibilityView> templateResponsibilities =
        responsibilityService.viewsOf(templates, caller, Map.of());

    List<AgentEntry> agentEntries =
        agents.stream()
            .map(
                agent -> {
                  AssetResponsibilityView responsibility = agentResponsibilities.get(agent.getId());
                  return new AgentEntry(
                      agent.getId(),
                      agent.getName(),
                      agent.getDescription(),
                      agent.getInstruction(),
                      namesOf(agent.getLibraryIds(), libraryNames),
                      // On the raw ids: a deleted library narrows the run to nothing, never
                      // widens it to everything readable (AgentToolLoop#scope).
                      agent.getLibraryIds().isEmpty(),
                      AgentTools.ASSIGNABLE.stream()
                          .filter(agent.getToolNames()::contains)
                          .toList(),
                      agent.getPlanStep(),
                      agent.configuration().limits(),
                      namesOf(agent.getTemplateIds(), templateNames),
                      agent.getRevision(),
                      agent.isDelivered(),
                      responsibilityOf(responsibility),
                      lockedSince(responsibility));
                })
            .toList();
    List<TemplateEntry> templateEntries =
        templates.stream()
            .map(
                template -> {
                  AssetResponsibilityView responsibility =
                      templateResponsibilities.get(template.getId());
                  return new TemplateEntry(
                      template.getId(),
                      template.getName(),
                      template.getDescription(),
                      template.getFileName(),
                      template.getPlaceholders().stream().map(TemplatePlaceholder::name).toList(),
                      template.getRevision(),
                      responsibilityOf(responsibility),
                      lockedSince(responsibility));
                })
            .toList();
    return new AgentDirectory(agentEntries, templatesIncluded, templateEntries);
  }

  /** The names of every assigned library that still exists in the organization, in one query. */
  private Map<UUID, String> libraryNames(List<Agent> agents, UUID organizationId) {
    Set<UUID> assigned = new HashSet<>();
    agents.forEach(agent -> assigned.addAll(agent.getLibraryIds()));
    Map<UUID, String> names = new HashMap<>();
    if (assigned.isEmpty()) {
      return names;
    }
    for (KnowledgeLibrary library : libraryRepository.findAllById(assigned)) {
      if (library.getOrganizationId().equals(organizationId) && !library.isOwnerOnly()) {
        names.put(library.getId(), library.getName());
      }
    }
    return names;
  }

  /** The names of the assigned ids that resolve, by name; a deleted object drops out. */
  private static List<String> namesOf(Set<UUID> ids, Map<UUID, String> names) {
    return ids.stream().map(names::get).filter(Objects::nonNull).sorted(BY_NAME).toList();
  }

  private static Responsibility responsibilityOf(AssetResponsibilityView view) {
    if (view == null) {
      return new Responsibility(null, null, true);
    }
    return new Responsibility(view.groupName(), view.function(), view.missing());
  }

  private static Instant lockedSince(AssetResponsibilityView view) {
    return view == null || view.lock() == null ? null : view.lock().lockedAt();
  }
}
