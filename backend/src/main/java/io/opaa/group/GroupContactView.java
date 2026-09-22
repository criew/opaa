package io.opaa.group;

/**
 * A contact point enriched with the person's display name, resolved from {@code UserRepository}
 * rather than carried by the entity. Domain counterpart of the generated {@code
 * GroupContactResponse}.
 */
public record GroupContactView(GroupContact contact, String displayName) {}
