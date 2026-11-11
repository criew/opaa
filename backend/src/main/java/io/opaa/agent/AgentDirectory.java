package io.opaa.agent;

import io.opaa.api.types.AgentPlanStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The agent directory of one organization: what every agent and document template is and does, for
 * the staff council. It describes objects, never people - no owner, no usage figure, no user, no
 * last use - and names libraries without their content.
 *
 * @param templatesIncluded whether the switch "Vorlagen" is on; off, {@code templates} is empty and
 *     no agent names a template
 */
public record AgentDirectory(
    List<AgentEntry> agents, boolean templatesIncluded, List<TemplateEntry> templates) {

  /**
   * The responsible unit; {@code groupName} as the reader may see it.
   *
   * @param missing whether the responsibility is missing right now
   */
  public record Responsibility(String groupName, String function, boolean missing) {}

  /**
   * One agent.
   *
   * @param libraryNames the assigned libraries that still exist, by name
   * @param allReadableLibraries whether no library is assigned at all, so the agent searches what
   *     the asking person may read; {@code false} with empty {@code libraryNames} means every
   *     assigned library is gone and the agent searches nothing
   * @param templateNames the assigned templates that still exist, by name
   * @param lockedSince {@code null} while the agent is not locked
   */
  public record AgentEntry(
      UUID id,
      String name,
      String description,
      String instruction,
      List<String> libraryNames,
      boolean allReadableLibraries,
      List<String> toolNames,
      AgentPlanStep planStep,
      AgentLimits limits,
      List<String> templateNames,
      int revision,
      boolean delivered,
      Responsibility responsibility,
      Instant lockedSince) {}

  /**
   * One document template.
   *
   * @param placeholderNames in the order they first appear in the file
   * @param lockedSince {@code null} while the template is not locked
   */
  public record TemplateEntry(
      UUID id,
      String name,
      String description,
      String fileName,
      List<String> placeholderNames,
      int revision,
      Responsibility responsibility,
      Instant lockedSince) {}
}
