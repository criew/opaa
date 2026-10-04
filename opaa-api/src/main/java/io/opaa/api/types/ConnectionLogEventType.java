package io.opaa.api.types;

/**
 * What happened to a connection, as the connection log records it (ADR-0041, Entscheidung 7). The
 * emergency shutdown writes one entry per connection it ends.
 */
public enum ConnectionLogEventType {
  CONNECTED,
  RECONNECTED,
  DISCONNECTED,
  EXPIRED,
  EMERGENCY_DISCONNECTED,
  DELETED
}
