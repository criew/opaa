package io.opaa.audit;

import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditSubjectKind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a revision response leaves out: every reference to a person. Not the stored pseudonym and no
 * per-response substitute either - two overlapping answers share event ids and times, so any
 * reference that names a person within an answer could be chained from answer to answer into a
 * lasting one
 * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt).
 *
 * <p>Left out are the actor (unless a system process, whose label names no person), a {@code USER}
 * subject, the object id of a {@code USER_ACCOUNT} object and the payload keys of {@link
 * AuditPayloadPersons#ALL} for their event types. That a person acted or was affected stays visible
 * through {@code actorKind} and {@code subjectKind}.
 */
public final class AuditResponseRedaction {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuditResponseRedaction() {}

  /** The fixed label of a system process; {@code null} for a person or service account. */
  public static String actorRef(AuditLogEntry entry) {
    return entry.getActorKind() == ActorKind.SYSTEM_PROCESS ? entry.getActorRef() : null;
  }

  /** A group's id; {@code null} for a person. */
  public static String subjectRef(AuditLogEntry entry) {
    return entry.getSubjectKind() == AuditSubjectKind.USER ? null : entry.getSubjectRef();
  }

  /** The object id; {@code null} for an account object, whose id is the person's pseudonym. */
  public static String objectId(AuditLogEntry entry) {
    return entry.getObjectType() == AuditObjectType.USER_ACCOUNT ? null : entry.getObjectId();
  }

  public static String before(AuditLogEntry entry) {
    return payload(entry, entry.getBefore());
  }

  public static String after(AuditLogEntry entry) {
    return payload(entry, entry.getAfter());
  }

  /** The payload without the keys naming a person; unchanged where its event type has none. */
  private static String payload(AuditLogEntry entry, String json) {
    if (json == null) {
      return null;
    }
    ObjectNode node = null;
    for (AuditPayloadPersons.PayloadKey key : AuditPayloadPersons.ALL) {
      if (!key.eventTypes().contains(entry.getEventType())) {
        continue;
      }
      if (node == null) {
        node = object(json);
        if (node == null) {
          return null;
        }
      }
      node.remove(key.key());
    }
    if (node == null) {
      return json;
    }
    return node.isEmpty() ? null : JSON.writeValueAsString(node);
  }

  private static ObjectNode object(String json) {
    try {
      JsonNode parsed = JSON.readTree(json);
      return parsed instanceof ObjectNode object ? object : null;
    } catch (JacksonException e) {
      return null;
    }
  }
}
