package io.opaa.api.types;

/**
 * The closed list of purposes the deletion of a locked account's chats may be held for
 * (docs/features/chat-list.md, "Anhalten der Löschung"). Mirrored by the database check constraints
 * {@code chk_chat_cleanup_holds_purpose}, {@code chk_chat_cleanup_hold_requests_purpose} and {@code
 * chk_audit_chat_cleanup_hold_purpose_counts_purpose}; keep all in sync.
 *
 * <p>As with {@link AuditIncidentScopePurpose}, the exclusion of disziplinarische,
 * arbeitsrechtliche and leistungsbezogene purposes is enforced by omission: no value names one.
 */
public enum ChatCleanupHoldPurpose {
  /** An order of an authority or a court to keep the data. */
  AUTHORITY_OR_COURT_ORDER,
  /** Securing evidence after a security incident. */
  SECURITY_INCIDENT_EVIDENCE,
  /** A pending request of the person for access to or erasure of her data. */
  DATA_SUBJECT_REQUEST
}
