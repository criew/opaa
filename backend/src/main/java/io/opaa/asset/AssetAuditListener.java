package io.opaa.asset;

import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
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

  /**
   * A grant to "Alle Konten" carries <b>no</b> audit subject: it names neither a person nor a
   * group, and {@code chk_audit_log_subject} knows only those two. The recipient is in the payload
   * instead, written by {@code AssetGrantService} (#1931, ADR-0037 Entscheidung 7) - and a grant to
   * everyone has nothing to do in the pseudonym table.
   */
  @EventListener
  void onGrantChanged(AssetGrantChanged event) {
    AssetGrant grant = event.grant();
    Asset asset = event.asset();
    AuditEvent.Builder builder =
        AuditEvent.builder()
            .organizationId(asset.getOrganizationId())
            .actor(event.actorUserId())
            .type(event.cause().auditEventType())
            .object(
                assetTypes.require(asset.getAssetType()).auditObjectType(),
                asset.getId(),
                asset.getName())
            .before(event.auditBefore())
            .after(event.auditAfter())
            .outcome(AuditOutcome.SUCCESS);
    if (grant.getSubjectType() == AssetGrantSubjectType.ALL_ACCOUNTS) {
      auditEventRecorder.recordUserAction(builder.build());
      return;
    }
    auditEventRecorder.recordUserActionOnSubject(
        builder
            .subject(
                grant.getSubjectType() == AssetGrantSubjectType.USER
                    ? AuditSubjectKind.USER
                    : AuditSubjectKind.GROUP,
                grant.getSubjectId())
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
