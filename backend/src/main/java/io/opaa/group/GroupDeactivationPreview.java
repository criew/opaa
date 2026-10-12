package io.opaa.group;

import java.util.UUID;

/**
 * What deactivating an internal group affects, counted - the figures its confirmation names
 * (ADR-0036, Entscheidung 14). Counts only, never a member's name.
 *
 * @param activeMembers the active accounts the group's rights reach while it is in effect
 * @param blockedReason why the group may not be deactivated right now, or null; always null for a
 *     deactivated group, whose reactivation is never refused
 */
public record GroupDeactivationPreview(
    UUID groupId,
    boolean deactivated,
    long assetGrants,
    long grantedAssets,
    long spaceMemberships,
    long spaces,
    long capabilities,
    int activeMembers,
    String blockedReason) {}
