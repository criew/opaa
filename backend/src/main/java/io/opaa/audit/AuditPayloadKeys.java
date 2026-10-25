package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import java.util.Set;

/**
 * Port to a writer whose payload keys follow from its own code - two keys per constant of an enum,
 * one per bean of a kind. It names them for {@link AuditEventCatalog} from the same source it
 * writes them from, so a new constant or bean is listed the moment it is written.
 */
public interface AuditPayloadKeys {

  /** The event type the keys are written under. */
  AuditEventType eventType();

  /** The keys, in addition to the fixed ones {@link AuditEventCatalog} lists for the type. */
  Set<String> keys();
}
