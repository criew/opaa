package io.opaa.group;

/**
 * Parameters for updating a group's mutable details - domain counterpart of the generated {@code
 * GroupUpdateRequest} at the {@link GroupService#updateGroup} boundary.
 */
public record GroupUpdate(String name, String description) {}
