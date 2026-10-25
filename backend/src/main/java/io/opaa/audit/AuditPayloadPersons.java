package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where an entry names a person it carries neither as actor nor as subject: under a key of {@code
 * before}/{@code after}, as pseudonym (entries written before the pseudonymisation of that key: as
 * account id) or, for a group, as group id. Only the self-disclosure searches these keys, each for
 * its event types only; the anlassbezogene Klärung finds the acting person alone.
 */
final class AuditPayloadPersons {

  /** The owner of a space or asset. */
  private static final PayloadKey OWNER =
      new PayloadKey(
          "ownerId",
          Set.of(
              AuditEventType.SPACE_CREATED,
              AuditEventType.SPACE_DELETED,
              AuditEventType.SPACE_ARCHIVED,
              AuditEventType.ASSET_OWNER_CHANGED,
              AuditEventType.ASSET_OWNERSHIP_CLAIMED,
              AuditEventType.ASSET_SUCCESSION_OPENED));

  /** The target of a transfer of rights. */
  private static final PayloadKey TRANSFER_TARGET =
      new PayloadKey(
          "targetId",
          Set.of(
              AuditEventType.PERMISSION_TRANSFER_PREVIEWED,
              AuditEventType.PERMISSION_TRANSFER_EXECUTED));

  /** Every key the self-disclosure searches and repeats only as "Sie" or another person. */
  static final List<PayloadKey> SELF_DISCLOSURE = List.of(OWNER, TRANSFER_TARGET);

  private AuditPayloadPersons() {}

  /** A payload key naming a person, and the event types in which it does. */
  record PayloadKey(String key, Set<AuditEventType> eventTypes) {

    /**
     * A {@code LIKE} pattern matching a stored payload that names {@code ref} under this key, or
     * {@code null} for none. The recorder writes compact JSON and escapes a quote inside a string
     * value, so only the key itself matches; a UUID holds no {@code LIKE} wildcard.
     */
    String pattern(UUID ref) {
      return ref == null ? null : "%\"" + key + "\":\"" + ref + "\"%";
    }

    String[] eventTypeNames() {
      return eventTypes.stream().map(Enum::name).sorted().toArray(String[]::new);
    }
  }
}
