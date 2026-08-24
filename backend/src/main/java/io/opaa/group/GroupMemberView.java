package io.opaa.group;

/**
 * A group membership enriched with the member's display name, resolved from {@code UserRepository}
 * and not part of the {@link GroupMembership} entity itself. Domain counterpart of the generated
 * {@code GroupMemberResponse}, mapped by {@code io.opaa.api.GroupResponseMapper}.
 */
public record GroupMemberView(GroupMembership membership, String displayName) {}
