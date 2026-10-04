package io.opaa.api.types;

/** Whose connection a connection-log entry is about. */
public enum ConnectionLogOwnerKind {
  /** A person's connected account; the entry names no library and no account. */
  PERSON,
  /** A library's source connection, with the service account's address. */
  LIBRARY,
  /** A connection held by the profile itself. */
  PROFILE
}
