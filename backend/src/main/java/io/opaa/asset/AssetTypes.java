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

  /** Every served type, in registration order. */
  public List<AssetType> registered() {
    return List.copyOf(byType.keySet());
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

  static String notFoundMessage(AssetTypeDefinition definition) {
    return definition.singular() + " nicht gefunden";
  }
}
