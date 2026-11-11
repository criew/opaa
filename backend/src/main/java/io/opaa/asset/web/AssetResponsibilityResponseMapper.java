package io.opaa.asset.web;

import io.opaa.api.dto.AssetLockResponse;
import io.opaa.api.dto.AssetResponsibilityResponse;
import io.opaa.api.dto.ResponsibleUnitRequest;
import io.opaa.asset.AssetResponsibilityView;
import io.opaa.asset.ResponsibleUnit;
import java.util.UUID;

/** Maps the responsible unit and the lock of agents and templates, for both their web packages. */
public final class AssetResponsibilityResponseMapper {

  private AssetResponsibilityResponseMapper() {}

  /** The submitted unit, {@code null} when the request names none. */
  public static ResponsibleUnit toUnit(ResponsibleUnitRequest request) {
    return request == null
        ? null
        : new ResponsibleUnit(request.getGroupId(), request.getFunction());
  }

  /** A unit from the two fields of a multipart form; {@code null} when both are absent. */
  public static ResponsibleUnit toUnit(UUID groupId, String function) {
    return groupId == null && function == null ? null : new ResponsibleUnit(groupId, function);
  }

  public static AssetResponsibilityResponse toResponse(AssetResponsibilityView view) {
    AssetResponsibilityResponse response =
        new AssetResponsibilityResponse(view.missing())
            .groupId(view.groupId())
            .groupName(view.groupName())
            .function(view.function())
            .missingSince(view.missingSince())
            .lockDueAt(view.lockDueAt());
    if (view.lock() != null) {
      response.lock(
          new AssetLockResponse(view.lock().cause(), view.lock().lockedAt())
              .reason(view.lock().reason()));
    }
    return response;
  }
}
