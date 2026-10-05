package io.opaa.connection.profile;

/**
 * What a profile connects: a connector with exactly one source type, or an MCP server without one
 * (ADR-0041, Entscheidung 5). The connector paths see only {@link #CONNECTOR} profiles.
 */
public enum ProfileKind {
  CONNECTOR,
  MCP_SERVER
}
