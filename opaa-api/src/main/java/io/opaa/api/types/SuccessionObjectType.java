package io.opaa.api.types;

/**
 * What an entry of the operational list is about. Deliberately its own vocabulary next to {@code
 * AssetType}: a group is no asset, and the list carries all three side by side.
 */
public enum SuccessionObjectType {
  KNOWLEDGE_LIBRARY,
  SPACE,
  GROUP
}
