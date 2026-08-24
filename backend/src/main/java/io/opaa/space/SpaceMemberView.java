package io.opaa.space;

/**
 * A space membership enriched with the member's display name, resolved from {@code UserRepository}
 * and not part of the {@link SpaceMembership} entity itself. Domain counterpart of the generated
 * {@code SpaceMemberResponse}, mapped by {@code io.opaa.api.SpaceResponseMapper}.
 */
public record SpaceMemberView(SpaceMembership membership, String displayName) {}
