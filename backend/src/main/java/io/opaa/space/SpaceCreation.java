package io.opaa.space;

import io.opaa.api.types.SpaceVisibility;
import java.util.List;
import java.util.UUID;

/**
 * Parameters for creating a space - domain counterpart of the generated {@code SpaceRequest} at the
 * {@link SpaceService#createSpace} boundary. {@code ownerId} may be {@code null} - {@link
 * SpaceService#createSpace} then defaults it to the caller. {@code chatAutoCleanup} {@code null}
 * leaves the automatic chat cleanup off.
 *
 * @param assets associated in the same transaction as the space; {@code null} means none
 */
public record SpaceCreation(
    String name,
    String description,
    UUID ownerId,
    SpaceVisibility visibility,
    List<SpaceMemberSeed> initialMembers,
    List<SpaceAssetSeed> assets,
    Boolean chatAutoCleanup) {

  public SpaceCreation(
      String name,
      String description,
      UUID ownerId,
      SpaceVisibility visibility,
      List<SpaceMemberSeed> initialMembers,
      List<SpaceAssetSeed> assets) {
    this(name, description, ownerId, visibility, initialMembers, assets, null);
  }
}
