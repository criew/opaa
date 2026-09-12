package io.opaa.api.types;

/**
 * How a single-use link reached - or did not reach - the person (ADR-0033, Entscheidung 11). The
 * audit event of the act carries the same value, so a hand-over outside the system stays
 * distinguishable from a delivery.
 */
public enum MailDeliveryPath {
  /** The mail went out; the link is not in the response. */
  MAIL_SENT,
  /** The send was attempted and failed; the link is in the response for hand-over. */
  MAIL_FAILED,
  /** No send was attempted (SMTP off or no public base URL); the link is in the response. */
  LINK_DISPLAYED
}
