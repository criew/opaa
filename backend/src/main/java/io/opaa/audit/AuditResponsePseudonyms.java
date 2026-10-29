package io.opaa.audit;

import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditSubjectKind;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The references to persons one revision response carries: every stored pseudonym (or, in older
 * payloads, account id) goes out as an HMAC-SHA256 under a key drawn at random for this response
 * and never kept. Within the response one person keeps one reference; across responses the
 * references of the same person differ, so no two answers can be joined on a person
 * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt).
 *
 * <p>Covered are the actor (unless a system process, whose label names no person), a {@code USER}
 * subject, the object id of a {@code USER_ACCOUNT} object and the payload keys of {@link
 * AuditPayloadPersons#ALL} for their event types. One instance serves exactly one response.
 */
public final class AuditResponsePseudonyms {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ALGORITHM = "HmacSHA256";

  /** 96 bits: no collision among the persons of one response, short enough to read. */
  private static final int REFERENCE_BYTES = 12;

  private final Mac mac;

  private AuditResponsePseudonyms(byte[] key) {
    try {
      mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  /** A fresh key for one response. */
  public static AuditResponsePseudonyms forOneResponse() {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    return new AuditResponsePseudonyms(key);
  }

  /** The actor's reference for this response; a system process keeps its fixed label. */
  public String actorRef(AuditLogEntry entry) {
    return entry.getActorKind() == ActorKind.SYSTEM_PROCESS
        ? entry.getActorRef()
        : reference(entry.getActorRef());
  }

  /** The subject's reference for this response; a group keeps its id. */
  public String subjectRef(AuditLogEntry entry) {
    return entry.getSubjectKind() == AuditSubjectKind.USER
        ? reference(entry.getSubjectRef())
        : entry.getSubjectRef();
  }

  /** The object id; an account object's id is the person's pseudonym and goes out keyed. */
  public String objectId(AuditLogEntry entry) {
    return entry.getObjectType() == AuditObjectType.USER_ACCOUNT
        ? reference(entry.getObjectId())
        : entry.getObjectId();
  }

  public String before(AuditLogEntry entry) {
    return payload(entry, entry.getBefore());
  }

  public String after(AuditLogEntry entry) {
    return payload(entry, entry.getAfter());
  }

  private String payload(AuditLogEntry entry, String json) {
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
          return json;
        }
      }
      JsonNode value = node.get(key.key());
      if (value != null && value.isString()) {
        node.put(key.key(), reference(value.asString()));
      }
    }
    return node == null ? json : JSON.writeValueAsString(node);
  }

  private static ObjectNode object(String json) {
    try {
      JsonNode parsed = JSON.readTree(json);
      return parsed instanceof ObjectNode object ? object : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  private String reference(String stored) {
    if (stored == null) {
      return null;
    }
    byte[] digest = mac.doFinal(stored.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest, 0, REFERENCE_BYTES);
  }
}
