package io.opaa.api.types;

/**
 * What an entry of the operational list is about. {@link #ASSET} stands for an asset of any type,
 * which the entry names separately - so a new asset type reaches the list without a value here. A
 * group is no asset, and the list carries all three side by side.
 */
public enum SuccessionObjectType {
  ASSET,
  SPACE,
  GROUP
}
