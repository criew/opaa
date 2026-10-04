package io.opaa.api.types;

/**
 * What an OAuth consent is started for (ADR-0025, Nachtrag 03.10.2026); the server derives where
 * the person returns to from it, never from a request.
 */
public enum ConnectionAuthorizationPurpose {
  /** A source for a library that is still being created. */
  LIBRARY_NEW,
  /** The source of an existing library, connected anew. */
  LIBRARY_RECONNECT,
  /** The caller's own connected account on a profile. */
  ACCOUNT
}
