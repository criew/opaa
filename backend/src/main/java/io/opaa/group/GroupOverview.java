package io.opaa.group;

import java.util.List;

/**
 * A group with its origin and its stewards resolved, for the list responses. {@code provider} is
 * null exactly for an internal group; the response's {@code origin} is derived from that.
 */
public record GroupOverview(
    Group group, List<GroupStewardView> stewards, GroupProviderView provider) {}
