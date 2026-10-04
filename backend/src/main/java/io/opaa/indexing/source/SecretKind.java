package io.opaa.indexing.source;

/** What a secret handed to a connector is, so the connector knows how to present it. */
public enum SecretKind {
  /** An app password, a personal token or user name and password, as entered. */
  PERSONAL_SECRET,
  /** A service account key the core signs with; it never reaches a connector (ADR-0040). */
  SERVICE_ACCOUNT_KEY,
  /** A short-lived access token the core obtained, e.g. by signing with a service account key. */
  ACCESS_TOKEN
}
