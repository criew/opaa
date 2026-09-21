package io.opaa.audit;

import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SpaceRole;
import java.time.Instant;
import java.util.UUID;

/**
 * One interval of the Stichtagsauskunft, already cut to the requested window (#1822): who reached
 * the object, on what basis, from when to when. {@code userId} is null for {@link
 * AccessBasis#ORGANIZATION_WIDE}, which reaches every account without naming one; {@code groupName}
 * is null once the group is gone - the history keeps its id, not a name snapshot.
 */
public record AccessAsOfEntry(
    AccessBasis basis,
    UUID userId,
    String userName,
    UUID groupId,
    String groupName,
    AssetRole assetRole,
    SpaceRole spaceRole,
    Instant validFrom,
    Instant validTo) {}
