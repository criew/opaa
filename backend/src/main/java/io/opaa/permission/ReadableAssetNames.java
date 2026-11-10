package io.opaa.permission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The name of an asset, a space or a group as a person's own data shows it, in the self-disclosure
 * and in the own lists: the name today only while the person may see the object today, otherwise
 * the neutral {@link #UNREADABLE}. A reference to an object the person no longer sees - a favorite,
 * a former grant, a space she left, a group she was removed from - thus never discloses what it is
 * called now. No system-admin floor applies. The rules are the existing ones:
 *
 * <ul>
 *   <li>an asset: the rights formula of {@link AssetAccessService};
 *   <li>a space ({@link #SPACE}): a role in it, as {@link SpaceVisibility} answers;
 *   <li>a group ({@link #GROUP}): a membership in effect, or the group may be named as a subject
 *       ({@link GroupSubjectDirectory#isSelectableBy}) and is not protected.
 * </ul>
 *
 * <p>A record names its objects through {@link #mark} (one per name field); {@link #apply} removes
 * every mark and replaces the names. A mark without an id or type, or a name that is already {@code
 * null} (a deleted object), keeps its value.
 */
@Component
public class ReadableAssetNames {

  /** What stands in place of the name of an object the person may not see. */
  public static final String UNREADABLE = "(kein Leserecht)";

  /** The type of a mark naming a space. */
  public static final String SPACE = "SPACE";

  /** The type of a mark naming a group. */
  public static final String GROUP = "GROUP";

  /** Hidden key of a record's marks; removed by {@link #apply}. */
  static final String MARKS = "_nameMarks";

  /** Hidden key of the asset id of the single-field form; removed by {@link #apply}. */
  public static final String ASSET_ID = "_assetId";

  /** Hidden key of the stored asset type of the single-field form; removed by {@link #apply}. */
  public static final String ASSET_TYPE = "_assetType";

  private final AssetAccessService access;
  private final SpaceVisibility spaces;
  private final GroupSubjectDirectory groups;
  private final GroupMembershipSource memberships;

  public ReadableAssetNames(
      AssetAccessService access,
      SpaceVisibility spaces,
      GroupSubjectDirectory groups,
      GroupMembershipSource memberships) {
    this.access = access;
    this.spaces = spaces;
    this.groups = groups;
    this.memberships = memberships;
  }

  /**
   * Marks {@code record} as naming the asset {@code assetId} of the stored type {@code type} under
   * the name key {@link #apply(UUID, List, String)} is given.
   */
  public static void mark(Map<String, Object> record, UUID assetId, String type) {
    if (assetId != null && type != null) {
      record.put(ASSET_ID, assetId);
      record.put(ASSET_TYPE, type);
    }
  }

  /**
   * Marks the value under {@code nameKey} of {@code record} as the name of the object {@code id}: a
   * stored asset type, {@link #SPACE} or {@link #GROUP}.
   */
  @SuppressWarnings("unchecked")
  public static void mark(Map<String, Object> record, String nameKey, UUID id, String type) {
    if (id != null && type != null) {
      ((Map<String, Mark>) record.computeIfAbsent(MARKS, k -> new LinkedHashMap<String, Mark>()))
          .put(nameKey, new Mark(id, type));
    }
  }

  /**
   * {@link #apply(UUID, List)}, with the single-field marks naming the value of {@code nameKey}.
   */
  public List<Map<String, Object>> apply(
      UUID userId, List<Map<String, Object>> records, String nameKey) {
    for (Map<String, Object> record : records) {
      UUID id = (UUID) record.remove(ASSET_ID);
      String type = (String) record.remove(ASSET_TYPE);
      mark(record, nameKey, id, type);
    }
    return apply(userId, records);
  }

  /**
   * Replaces every marked name of {@code records} whose object {@code userId} may not see today by
   * {@link #UNREADABLE}, with one lookup per object type.
   *
   * @return {@code records}, changed in place
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> apply(UUID userId, List<Map<String, Object>> records) {
    List<Map<String, Object>> owners = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    List<Mark> marks = new ArrayList<>();
    Map<String, Set<UUID>> idsByType = new HashMap<>();
    for (Map<String, Object> record : records) {
      Map<String, Mark> recordMarks = (Map<String, Mark>) record.remove(MARKS);
      if (recordMarks == null) {
        continue;
      }
      recordMarks.forEach(
          (key, mark) -> {
            if (record.get(key) != null) {
              owners.add(record);
              keys.add(key);
              marks.add(mark);
              idsByType.computeIfAbsent(mark.type(), t -> new HashSet<>()).add(mark.id());
            }
          });
    }
    Map<String, Set<UUID>> readableByType = new HashMap<>();
    idsByType.forEach((type, ids) -> readableByType.put(type, readableAmong(type, ids, userId)));
    for (int i = 0; i < owners.size(); i++) {
      if (!readableByType.get(marks.get(i).type()).contains(marks.get(i).id())) {
        owners.get(i).put(keys.get(i), UNREADABLE);
      }
    }
    return records;
  }

  /**
   * The ids among {@code ids} of the type {@code type} - a stored asset type, {@link #SPACE} or
   * {@link #GROUP} - whose name {@code userId} may see today, for a list that resolves names
   * itself.
   */
  public Set<UUID> readableAmong(String type, Set<UUID> ids, UUID userId) {
    if (ids.isEmpty()) {
      return new HashSet<>();
    }
    return switch (type) {
      case SPACE -> new HashSet<>(spaces.visibleAmong(ids, userId));
      case GROUP -> readableGroups(ids, userId);
      default -> readableAssets(type, ids, userId);
    };
  }

  private Set<UUID> readableAssets(String type, Collection<UUID> assetIds, UUID userId) {
    Set<UUID> readable = new HashSet<>();
    access
        .effectiveRoles(AssetType.of(type), Set.copyOf(assetIds), userId)
        .forEach(
            (assetId, role) -> {
              if (role != null) {
                readable.add(assetId);
              }
            });
    return readable;
  }

  /**
   * A group in effect for the person is always named; any other one only if she may name it as a
   * subject and it carries no protection, whose name is withheld from everybody outside it.
   */
  private Set<UUID> readableGroups(Collection<UUID> groupIds, UUID userId) {
    Set<UUID> own = memberships.findGroupIdsByUserId(userId);
    Set<UUID> readable = new HashSet<>();
    for (UUID groupId : groupIds) {
      if (own.contains(groupId)) {
        readable.add(groupId);
        continue;
      }
      boolean isProtected = groups.find(groupId).map(GroupSubject::protectedGroup).orElse(true);
      if (!isProtected && groups.isSelectableBy(groupId, userId, false)) {
        readable.add(groupId);
      }
    }
    return readable;
  }

  private record Mark(UUID id, String type) {}
}
