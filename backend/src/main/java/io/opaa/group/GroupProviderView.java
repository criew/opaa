package io.opaa.group;

import java.util.UUID;

/**
 * The identity provider a group originates from, as a response needs it (ADR-0036, Entscheidung 2).
 * {@code enabled} is why a response can say why a group is not selectable: the groups of a disabled
 * provider are no effective groups - no new grant target and no new space member - while their
 * existing grants stay untouched.
 */
public record GroupProviderView(UUID id, String displayName, boolean external, boolean enabled) {}
