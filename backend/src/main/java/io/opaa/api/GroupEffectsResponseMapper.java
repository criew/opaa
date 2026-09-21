package io.opaa.api;

import io.opaa.api.dto.GroupEffectsResponse;
import io.opaa.api.types.GroupOrigin;
import io.opaa.group.Group;
import io.opaa.group.GroupEffectsView;
import java.util.List;

/**
 * Maps the per-group effect counts onto their response. The origin is derived the same way as in
 * {@link GroupResponseMapper}: a group with a provider is a PROVIDER group, one without is
 * INTERNAL.
 */
final class GroupEffectsResponseMapper {

  private GroupEffectsResponseMapper() {}

  static GroupEffectsResponse toResponse(GroupEffectsView view) {
    Group group = view.group();
    return new GroupEffectsResponse(
            group.getId(),
            group.getName(),
            group.getProviderId() == null ? GroupOrigin.INTERNAL : GroupOrigin.PROVIDER,
            group.isDissolved(),
            group.isProtectedGroup(),
            (int) view.assetGrants(),
            (int) view.grantedAssets(),
            (int) view.spaceMemberships(),
            (int) view.spaces(),
            (int) view.ownedAssets(),
            (int) view.capabilities(),
            (int) view.scopedAuthorizations(),
            view.describe())
        .providerId(group.getProviderId())
        .sourcePath(group.getSourcePath());
  }

  static List<GroupEffectsResponse> toResponses(List<GroupEffectsView> views) {
    return views.stream().map(GroupEffectsResponseMapper::toResponse).toList();
  }
}
