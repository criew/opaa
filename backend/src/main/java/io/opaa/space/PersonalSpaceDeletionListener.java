package io.opaa.space;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.local.LocalAccountDeletionEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Removes the spaces a deleted local account owns - {@code fk_spaces_owner_organization} is
 * RESTRICT and leaves no choice. Each one is audited as {@code SPACE_DELETED}, like any space
 * deletion. Runs in the deletion's transaction; the rows go with the deletion's flush.
 */
@Component
class PersonalSpaceDeletionListener {

  private final SpaceRepository spaces;
  private final AuditEventRecorder audit;

  PersonalSpaceDeletionListener(SpaceRepository spaces, AuditEventRecorder audit) {
    this.spaces = spaces;
    this.audit = audit;
  }

  @Order(LocalAccountDeletionEvent.PERSONAL_SPACES_ORDER)
  @EventListener
  void onAccountDeletion(LocalAccountDeletionEvent event) {
    List<Space> personal = spaces.findByOwnerId(event.user().getId());
    for (Space space : personal) {
      audit.recordUserAction(
          AuditEvent.builder()
              .organizationId(space.getOrganizationId())
              .actor(event.actor().id())
              .type(AuditEventType.SPACE_DELETED)
              .object(AuditObjectType.SPACE, space.getId(), space.getName())
              .before(spaceAuditPayload(space))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    spaces.deleteAll(personal);
  }

  /** The same payload {@code SpaceService} writes for a space deletion. */
  private static Map<String, Object> spaceAuditPayload(Space space) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", space.getName());
    payload.put("visibility", space.getVisibility().name());
    payload.put("ownerId", space.getOwnerId().toString());
    return payload;
  }
}
