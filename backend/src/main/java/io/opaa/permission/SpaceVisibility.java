package io.opaa.permission;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Which spaces a person sees today, for {@link ReadableAssetNames} without knowing {@code
 * io.opaa.space}: the spaces in which she has a role - as owner, directly or through a group in
 * effect - by the rule the space list applies. No system-admin floor. Implemented by {@code
 * SpaceVisibilityAdapter}.
 */
public interface SpaceVisibility {

  /** The ids among {@code spaceIds} in which {@code userId} has a role today. */
  Set<UUID> visibleAmong(Collection<UUID> spaceIds, UUID userId);
}
