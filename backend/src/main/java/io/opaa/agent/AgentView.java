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
 * @param hiddenLibraryCount assigned libraries the caller may not read, or that no longer exist;
 *     their names are never shown, only how many there are
 * @param ownerName {@code null} when the owner has no name the caller may see
 * @param succession {@code null} while the agent has a capable owner
 */
public record AgentView(
    Agent agent,
    AssetRole myRole,
    List<LibraryRef> libraries,
    int hiddenLibraryCount,
    String ownerName,
    SuccessionFinding succession,
    AssetReach reach) {

  /** A library the caller may read. */
  public record LibraryRef(UUID id, String name) {}
}
