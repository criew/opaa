package io.opaa.space;

import java.util.List;

/**
 * The "Zuordnungen" of one asset (#1939): the associations this caller may be shown, and how many
 * further ones they may not. {@code hiddenCount} counts the PRIVATE spaces the caller is no member
 * of - naming them would break the promise of that visibility, that only its members know the space
 * exists. It is always {@code 0} for a caller who may manage the asset.
 */
public record AssetSpaceLinks(List<AssetSpaceLink> items, int hiddenCount) {}
