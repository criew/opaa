package io.opaa.query.toolloop;

/**
 * The rights class every {@link AgentTool} declares (ADR-0070, Entscheidung 2). A tool without one
 * keeps the application from starting ({@link ToolRegistry}).
 */
public enum ToolRightsClass {
  /** Reads the own bestand with the caller's rights, or asks the person back. */
  READING,
  /** Runs isolated from the bestand and the network; its files only become part of the answer. */
  ISOLATED,
  /** Acts outside the run. None exists yet; the first one needs the confirmation of ADR-0070. */
  WRITING
}
