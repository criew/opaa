package io.opaa.space;

/**
 * Parameters for updating a space's mutable details - domain counterpart of the generated {@code
 * SpaceUpdateRequest} at the {@link SpaceService#updateSpace} boundary. A {@code null} {@code
 * chatAutoCleanup} or {@code modelInstruction} leaves that setting unchanged; a blank {@code
 * modelInstruction} removes the instruction.
 */
public record SpaceUpdate(
    String name, String description, Boolean chatAutoCleanup, String modelInstruction) {

  public SpaceUpdate(String name, String description, Boolean chatAutoCleanup) {
    this(name, description, chatAutoCleanup, null);
  }

  public SpaceUpdate(String name, String description) {
    this(name, description, null, null);
  }
}
