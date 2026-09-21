package io.opaa.group;

import java.util.List;

/**
 * A group enriched with its members' and stewards' display names and its origin, resolved outside
 * the {@link Group} entity itself. Domain counterpart of the generated {@code GroupResponse},
 * mapped by {@code io.opaa.api.GroupResponseMapper}; {@code provider} is null exactly for an
 * internal group, and {@code stewards} is empty for a provider group and for an internal group
 * whose succession is open.
 */
public record GroupDetail(
    Group group,
    List<GroupMemberView> members,
    List<GroupStewardView> stewards,
    GroupProviderView provider) {}
