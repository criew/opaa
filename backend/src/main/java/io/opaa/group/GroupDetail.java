package io.opaa.group;

import java.util.List;

/**
 * A group enriched with its members' display names, resolved outside the {@link Group} entity
 * itself. Domain counterpart of the generated {@code GroupResponse}, mapped by {@code
 * io.opaa.api.GroupResponseMapper}.
 */
public record GroupDetail(Group group, List<GroupMemberView> members) {}
