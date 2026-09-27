package io.opaa.directory;

import java.util.UUID;

/**
 * What deleting an identity provider needs to know about its groups. Implemented by {@code
 * ProviderGroupDirectoryAdapter} over the repositories of {@code io.opaa.group}, so the provider
 * administration itself holds no group.
 *
 * <p>{@code groups.provider_id} is {@code ON DELETE RESTRICT} (ADR-0036, Entscheidung 2): a
 * provider is only deletable once its groups are gone. Groups without effect go with it; a group
 * that still carries a right refuses the deletion with a 409.
 */
public interface ProviderGroupDirectory {

  /** What the provider's groups still do - never null, empty when it has no groups at all. */
  ProviderGroupEffects effectsOf(UUID providerId);

  /**
   * Deletes every group of the provider, with its memberships and open history intervals. Only
   * called once {@link #effectsOf} reported no effect, so nothing that still carries a right is
   * removed here.
   */
  void deleteGroupsOfProvider(UUID providerId, UUID actorUserId);
}
