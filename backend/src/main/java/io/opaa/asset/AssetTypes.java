package io.opaa.asset;

import io.opaa.common.NotFoundException;
import io.opaa.permission.AssetType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The registered {@link AssetTypeDefinition}s, by key. An asset type without a definition is not
 * served: every request naming it answers like an unknown asset.
 *
 * <p>Registered is not the same as active (ADR-0044): an inactive type keeps its definition for the
 * paths that must handle existing assets whatever their type - ownership transfer, group and
 * account removal, history, audit - while every read interface asks {@link #active()} or {@link
 * #isActive} and a request path naming it gets the {@code 404} of {@link #requireActive(String)}.
 */
@Component
public class AssetTypes {

  private final Map<AssetType, AssetTypeDefinition> byType = new LinkedHashMap<>();

  public AssetTypes(List<AssetTypeDefinition> definitions) {
    for (AssetTypeDefinition definition : definitions) {
      if (byType.put(definition.assetType(), definition) != null) {
        throw new IllegalStateException("two definitions for " + definition.assetType());
      }
    }
  }

  /** Every registered type, active or not, in registration order. */
  public List<AssetType> registered() {
    return List.copyOf(byType.keySet());
  }

  /** Every type offered to readers right now, in registration order. */
  public List<AssetType> active() {
    return byType.values().stream()
        .filter(AssetTypeDefinition::enabled)
        .map(AssetTypeDefinition::assetType)
        .toList();
  }

  /** Whether {@code assetType} is registered and offered to readers right now. */
  public boolean isActive(AssetType assetType) {
    return find(assetType).map(AssetTypeDefinition::enabled).orElse(false);
  }

  public Optional<AssetTypeDefinition> find(AssetType assetType) {
    return Optional.ofNullable(byType.get(assetType));
  }

  /** The definition of {@code assetType}, or the {@code 404} an unknown asset gets. */
  public AssetTypeDefinition require(AssetType assetType) {
    return find(assetType).orElseThrow(() -> new NotFoundException("Objekt nicht gefunden"));
  }

  /** The definition of a type named by a request path - malformed or unknown answers 404. */
  public AssetTypeDefinition require(String assetType) {
    try {
      return require(AssetType.of(assetType));
    } catch (IllegalArgumentException malformed) {
      throw new NotFoundException("Objekt nicht gefunden");
    }
  }

  /**
   * The definition of an active type named by a request path - malformed, unknown and inactive
   * alike answer 404, so a switched-off type cannot be told from one that never existed.
   */
  public AssetTypeDefinition requireActive(String assetType) {
    AssetTypeDefinition definition = require(assetType);
    if (!definition.enabled()) {
      throw new NotFoundException("Objekt nicht gefunden");
    }
    return definition;
  }

  static String notFoundMessage(AssetTypeDefinition definition) {
    return definition.singular() + " nicht gefunden";
  }
}
