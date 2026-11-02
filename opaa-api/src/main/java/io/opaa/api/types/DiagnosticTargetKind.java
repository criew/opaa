package io.opaa.api.types;

/**
 * Whose rights context a search diagnosis was executed in (ADR-0065, Entscheidung 9d). {@code
 * PERMISSION_PROFILE} is the default - a role plus library set, belonging to nobody; {@code USER}
 * is the exception that requires a separately granted befugnis and a free-text justification. A
 * diagnosis in the caller's own context is neither: it is not a foreign context at all and produces
 * no protocol entry.
 */
public enum DiagnosticTargetKind {
  PERMISSION_PROFILE,
  USER
}
