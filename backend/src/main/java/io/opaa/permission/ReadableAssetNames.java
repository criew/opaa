package io.opaa.permission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The name of an asset as a person's own data shows it, in the self-disclosure and in the own
 * lists: the asset's name today only while the person may read the asset by the rights formula of
 * {@link AssetAccessService}, otherwise the neutral {@link #UNREADABLE}. A reference to an asset
 * the person no longer reads - a favorite, a former grant, an upload into a library she left - thus
 * never discloses what the asset is called now. No system-admin floor applies.
 *
 * <p>A record names its asset under the hidden keys {@link #ASSET_ID} and {@link #ASSET_TYPE}
 * ({@link #mark}); {@link #apply} removes both and replaces the name. A record without a mark, or
 * with a name that is already {@code null} (a deleted asset), keeps its value.
 */
@Component
public class ReadableAssetNames {

  /** What stands in place of the name of an asset the person may not read. */
  public static final String UNREADABLE = "(kein Leserecht)";

  /** Hidden key of the asset id in a record; removed by {@link #apply}. */
  public static final String ASSET_ID = "_assetId";

  /** Hidden key of the stored asset type in a record; removed by {@link #apply}. */
  public static final String ASSET_TYPE = "_assetType";

  private final AssetAccessService access;

  public ReadableAssetNames(AssetAccessService access) {
    this.access = access;
  }

  /** Marks {@code record} as naming the asset {@code assetId} of the stored type {@code type}. */
  public static void mark(Map<String, Object> record, UUID assetId, String type) {
    if (assetId != null && type != null) {
      record.put(ASSET_ID, assetId);
      record.put(ASSET_TYPE, type);
    }
  }

  /**
   * Replaces the value under {@code nameKey} of every marked record of {@code records} whose asset
   * {@code userId} may not read by {@link #UNREADABLE}, with one rights query per asset type.
   *
   * @return {@code records}, changed in place
   */
  public List<Map<String, Object>> apply(
      UUID userId, List<Map<String, Object>> records, String nameKey) {
    List<Map<String, Object>> marked = new ArrayList<>();
    List<UUID> ids = new ArrayList<>();
    List<String> types = new ArrayList<>();
    Map<String, Set<UUID>> idsByType = new HashMap<>();
    for (Map<String, Object> record : records) {
      UUID id = (UUID) record.remove(ASSET_ID);
      String type = (String) record.remove(ASSET_TYPE);
      if (id == null || record.get(nameKey) == null) {
        continue;
      }
      marked.add(record);
      ids.add(id);
      types.add(type);
      idsByType.computeIfAbsent(type, t -> new HashSet<>()).add(id);
    }
    Map<String, Set<UUID>> readableByType = new HashMap<>();
    idsByType.forEach(
        (type, assetIds) -> readableByType.put(type, readableAmong(type, assetIds, userId)));
    for (int i = 0; i < marked.size(); i++) {
      if (!readableByType.get(types.get(i)).contains(ids.get(i))) {
        marked.get(i).put(nameKey, UNREADABLE);
      }
    }
    return records;
  }

  /**
   * The ids among {@code assetIds} of the stored type {@code type} that {@code userId} may read,
   * for a list that resolves names itself.
   */
  public Set<UUID> readableAmong(String type, Set<UUID> assetIds, UUID userId) {
    Set<UUID> readable = new HashSet<>();
    if (assetIds.isEmpty()) {
      return readable;
    }
    access
        .effectiveRoles(AssetType.of(type), assetIds, userId)
        .forEach(
            (assetId, role) -> {
              if (role != null) {
                readable.add(assetId);
              }
            });
    return readable;
  }
}
