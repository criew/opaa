package io.opaa.agent.web;

import io.opaa.agent.AgentDirectory;
import io.opaa.agent.AgentLimits;
import io.opaa.api.dto.AgentDirectoryEntry;
import io.opaa.api.dto.AgentDirectoryResponse;
import io.opaa.api.dto.AgentDirectoryResponsibility;
import io.opaa.api.dto.DocumentTemplateDirectoryEntry;

/** The agent directory as the API shows it: field for field, no owner and no usage figure. */
final class AgentDirectoryResponseMapper {

  private AgentDirectoryResponseMapper() {}

  static AgentDirectoryResponse toResponse(AgentDirectory directory) {
    return new AgentDirectoryResponse()
        .agents(directory.agents().stream().map(AgentDirectoryResponseMapper::toEntry).toList())
        .templatesIncluded(directory.templatesIncluded())
        .templates(
            directory.templates().stream().map(AgentDirectoryResponseMapper::toEntry).toList());
  }

  private static AgentDirectoryEntry toEntry(AgentDirectory.AgentEntry agent) {
    AgentLimits limits = agent.limits();
    return new AgentDirectoryEntry()
        .id(agent.id())
        .name(agent.name())
        .description(agent.description())
        .instruction(agent.instruction() == null ? "" : agent.instruction())
        .libraryNames(agent.libraryNames())
        .allReadableLibraries(agent.allReadableLibraries())
        .toolNames(agent.toolNames())
        .planStep(agent.planStep())
        .limits(
            new io.opaa.api.dto.AgentLimits()
                .maxToolCalls(limits.maxToolCalls())
                .maxSearchCalls(limits.maxSearchCalls())
                .maxDurationSeconds(limits.maxDurationSeconds())
                .maxCollectedChars(limits.maxCollectedChars()))
        .templateNames(agent.templateNames())
        .revision(agent.revision())
        .delivered(agent.delivered())
        .responsibility(toResponsibility(agent.responsibility()))
        .locked(agent.lockedSince() != null)
        .lockedSince(agent.lockedSince());
  }

  private static DocumentTemplateDirectoryEntry toEntry(AgentDirectory.TemplateEntry template) {
    return new DocumentTemplateDirectoryEntry()
        .id(template.id())
        .name(template.name())
        .description(template.description())
        .fileName(template.fileName())
        .placeholderNames(template.placeholderNames())
        .revision(template.revision())
        .responsibility(toResponsibility(template.responsibility()))
        .locked(template.lockedSince() != null)
        .lockedSince(template.lockedSince());
  }

  private static AgentDirectoryResponsibility toResponsibility(
      AgentDirectory.Responsibility responsibility) {
    return new AgentDirectoryResponsibility()
        .groupName(responsibility.groupName())
        .function(responsibility.function())
        .missing(responsibility.missing());
  }
}
