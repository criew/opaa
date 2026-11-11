package io.opaa.agent;

import io.opaa.api.types.AssetRole;
import io.opaa.permission.AssetReach;
import io.opaa.permission.SuccessionFinding;
import java.util.List;
import java.util.UUID;

/**
 * An agent as the caller sees it.
 *
 * @param libraries the assigned libraries the caller may read, by name
 * @param hiddenLibraryCount assigned libraries that exist and the caller may not read; their names
 *     are never shown, only how many there are
 * @param templates the assigned document templates the caller may read, by name
 * @param hiddenTemplateCount assigned templates the caller may not read; counted, never named
 * @param ownerName {@code null} when the owner has no name the caller may see
 * @param succession {@code null} while the agent has a capable owner
 * @param instructionReadable whether the caller may read the instruction, which is content: the
 *     system administration's management alone does not open it
 */
public record AgentView(
    Agent agent,
    AssetRole myRole,
    List<LibraryRef> libraries,
    int hiddenLibraryCount,
    String ownerName,
    SuccessionFinding succession,
    AssetReach reach,
    boolean instructionReadable,
    List<TemplateRef> templates,
    int hiddenTemplateCount) {

  /** A library the caller may read. */
  public record LibraryRef(UUID id, String name) {}

  /** A document template the caller may read. */
  public record TemplateRef(UUID id, String name) {}
}
