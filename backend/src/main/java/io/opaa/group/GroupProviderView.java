package io.opaa.group;

import io.opaa.api.types.GroupMechanism;
import java.time.Instant;
import java.util.UUID;

/**
 * The identity provider a group originates from, as a response needs it (ADR-0036, Entscheidung 2).
 * {@code enabled} is why a response can say why a group is not selectable: the groups of a disabled
 * provider are no effective groups - no new grant target and no new space member - while their
 * existing grants stay untouched.
 *
 * <p>{@code mechanism}, {@code syncIntervalMinutes} and {@code lastSyncAt} make the delay of the
 * directory run visible to every member, not only to the system administration (ADR-0036,
 * Entscheidung 3): a colleague newly taken into a unit sees why nothing has arrived yet instead of
 * calling the IT department. The two latter fields are null unless the mechanism is {@link
 * GroupMechanism#DIRECTORY}, {@code lastSyncAt} additionally until that provider has run once.
 */
public record GroupProviderView(
    UUID id,
    String displayName,
    boolean external,
    boolean enabled,
    GroupMechanism mechanism,
    Integer syncIntervalMinutes,
    Instant lastSyncAt) {}
