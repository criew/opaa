package io.opaa.api.types;

/**
 * {@code AUDITOR} is #393's revision role: it grants read access to the four bounded revision query
 * paths and the anlassbezogene-Klärung workflow in {@code io.opaa.audit} (see
 * docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt) and
 * nothing else - it carries none of {@code SYSTEM_ADMIN}'s administrative privileges, and {@code
 * SYSTEM_ADMIN} carries none of {@code AUDITOR}'s read access either. Kept as a single value on the
 * existing role enum rather than a new authorization concept: the {@code hasRole(...)} mechanism
 * {@code AdminController} already uses is enough to enforce the technically separate access path
 * the specification requires for revision (the Dienststellenleitung cockpit is a distinct,
 * not-yet-built feature per docs/features/monitoring-and-governance.md and therefore not a role
 * this enum needs to distinguish from yet).
 */
public enum SystemRole {
  USER,
  SYSTEM_ADMIN,
  AUDITOR
}
