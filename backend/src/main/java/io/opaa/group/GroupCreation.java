package io.opaa.group;

/**
 * Parameters for creating an ad-hoc group - domain counterpart of the generated {@code
 * GroupRequest} at the {@link GroupService#createGroup} boundary.
 */
public record GroupCreation(String name, String description) {}
