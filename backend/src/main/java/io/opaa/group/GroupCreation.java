package io.opaa.group;

import java.util.List;
import java.util.UUID;

/**
 * Parameters for creating an ad-hoc group - domain counterpart of the generated {@code
 * GroupRequest} at the {@link GroupService#createGroup} boundary. {@code stewardIds} empty means
 * the caller alone.
 */
public record GroupCreation(
    String name,
    String description,
    boolean releasedForUse,
    boolean protectedGroup,
    List<UUID> stewardIds) {

  public GroupCreation {
    stewardIds = stewardIds == null ? List.of() : List.copyOf(stewardIds);
  }

  /** A group with nothing decided yet: not released, not protected, the caller its steward. */
  public GroupCreation(String name, String description) {
    this(name, description, false, false, List.of());
  }
}
