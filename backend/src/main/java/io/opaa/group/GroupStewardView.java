package io.opaa.group;

/**
 * A stewardship enriched with the responsible person's display name, resolved from {@code
 * UserRepository} and not part of the {@link GroupSteward} entity itself. Domain counterpart of the
 * generated {@code GroupStewardResponse}.
 */
public record GroupStewardView(GroupSteward steward, String displayName) {}
