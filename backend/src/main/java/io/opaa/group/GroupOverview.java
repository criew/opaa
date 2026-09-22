package io.opaa.group;

import java.util.List;

/**
 * A group with its origin, its stewards and - for a provider group - its contact points resolved,
 * for the list responses. {@code provider} is null exactly for an internal group; the response's
 * {@code origin} is derived from that.
 */
public record GroupOverview(
    Group group,
    List<GroupStewardView> stewards,
    List<GroupContactView> contacts,
    GroupProviderView provider) {}
