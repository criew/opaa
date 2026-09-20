package io.opaa.group;

/**
 * A group with its origin resolved, for the list responses. {@code provider} is null exactly for an
 * internal group; the response's {@code origin} is derived from that.
 */
public record GroupOverview(Group group, GroupProviderView provider) {}
