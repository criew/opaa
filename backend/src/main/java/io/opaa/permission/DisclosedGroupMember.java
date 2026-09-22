package io.opaa.permission;

import java.util.UUID;

/**
 * One member of a group, as much as the person granting that group a right needs to know: who
 * (#1880). No account state and no membership date - the question is the reach of the own grant,
 * not the personnel record of a stranger.
 *
 * @param displayName the account's display name, its mail address where it carries none; null only
 *     where neither is resolvable.
 */
public record DisclosedGroupMember(UUID userId, String displayName) {}
