package io.opaa.api.types;

/**
 * Where an asset comes from, mirrored by {@code chk_assets_origin}: created in this installation,
 * or delivered with it. Nothing creates a {@link #BUILT_IN} asset yet.
 */
public enum AssetOrigin {
  LOCAL,
  BUILT_IN
}
