package io.opaa.asset;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.permission.GroupSubjectDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The owners' display names for a list of assets of any type, in one query per owner kind. A list
 * of assets reaches every one of their readers, so a person without a display name stays unnamed
 * rather than falling back to an e-mail address, and a protected group stays nameless (ADR-0036,
 * Entscheidung 9). An owner that cannot be resolved has no entry.
 */
@Component
public class AssetOwnerNames {

  private final UserRepository userRepository;
  private final GroupSubjectDirectory groupDirectory;

  public AssetOwnerNames(UserRepository userRepository, GroupSubjectDirectory groupDirectory) {
    this.userRepository = userRepository;
    this.groupDirectory = groupDirectory;
  }

  /** Display name by owner id (person or group). */
  public Map<UUID, String> of(Collection<? extends Asset> assets) {
    Set<UUID> userOwnerIds = new HashSet<>();
    Set<UUID> groupOwnerIds = new HashSet<>();
    for (Asset asset : assets) {
      if (asset.getOwnerType() == AssetOwnerType.USER) {
        userOwnerIds.add(asset.getOwnerId());
      } else {
        groupOwnerIds.add(asset.getOwnerId());
      }
    }
    Map<UUID, String> ownerNames = new HashMap<>();
    for (User user : userRepository.findAllById(userOwnerIds)) {
      if (user.getDisplayName() != null) {
        ownerNames.put(user.getId(), user.getDisplayName());
      }
    }
    ownerNames.putAll(groupDirectory.displayNamesById(groupOwnerIds));
    return ownerNames;
  }
}
