package io.opaa.api.types;

/**
 * The two Confluence editions a {@code CONFLUENCE} library can point at (ADR-0023, Entscheidung 2).
 * Detected by the connection test from the instance's own response signature, persisted at library
 * creation and immutable afterwards - each edition has its own REST base path, authentication
 * scheme and pagination model, served by its own adapter.
 */
public enum ConfluenceEdition {
  /**
   * Atlassian-hosted Confluence Cloud: {@code /wiki/api/v2}, HTTP Basic from e-mail + API token.
   */
  CLOUD,
  /**
   * Self-hosted Confluence Data Center (or Server): {@code /rest/api}, Bearer personal access
   * token.
   */
  DATA_CENTER
}
