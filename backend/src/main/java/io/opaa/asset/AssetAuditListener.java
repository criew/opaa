package io.opaa.asset;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.permission.AssetGrant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The audit half of {@link AssetGrantChanged}'s and {@link AssetChanged}'s double bookkeeping. A
 * plain {@code @EventListener}: it runs in the publisher's transaction and rolls back with it.
 */
@Component
class AssetAuditListener {

  private final AuditEventRecorder auditEventRecorder;
  private final AssetTypes assetTypes;

  AssetAuditListener(AuditEventRecorder auditEventRecorder, AssetTypes assetTypes) {
    this.auditEventRecorder = auditEventRecorder;
    this.assetTypes = assetTypes;
  }

  @EventListener
  void onGrantChanged(AssetGrantChanged event) {
    AssetGrant grant = event.grant();
    Asset asset = event.asset();
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(asset.getOrganizationId())
            .actor(event.actorUserId())
            .type(event.cause().auditEventType())
            .object(
                assetTypes.require(asset.getAssetType()).auditObjectType(),
                asset.getId(),
                asset.getName())
            .subject(
                grant.getSubjectType() == PermissionSubjectType.USER
                    ? AuditSubjectKind.USER
                    : AuditSubjectKind.GROUP,
                grant.getSubjectId())
            .before(event.auditBefore())
            .after(event.auditAfter())
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  @EventListener
  void onAssetChanged(AssetChanged event) {
    Asset asset = event.asset();
    AssetTypeDefinition definition = assetTypes.require(asset.getAssetType());
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(asset.getOrganizationId())
            .actor(event.actorUserId())
            .type(
                event.cause() == AssetChanged.Cause.CREATED
                    ? definition.createdAuditEventType()
                    : AuditEventType.ASSET_VISIBILITY_CHANGED)
            .object(definition.auditObjectType(), asset.getId(), asset.getName())
            .before(event.auditBefore())
            .after(event.auditAfter())
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
