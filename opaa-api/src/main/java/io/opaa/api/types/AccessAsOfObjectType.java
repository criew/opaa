package io.opaa.api.types;

/**
 * The kinds of object the Stichtagsauskunft answers about (#1822, ADR-0036 Entscheidung 8): a
 * library answers "who could read it", a space "who was a member". Deliberately narrower than
 * {@link AuditObjectType}: exactly one named object per query, and only where a Rechtehistorie
 * exists to answer from (Personalrat Z3).
 */
public enum AccessAsOfObjectType {
  KNOWLEDGE_LIBRARY,
  SPACE
}
