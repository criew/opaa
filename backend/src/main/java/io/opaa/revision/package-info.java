/**
 * The revision's own reading paths into the Rechtehistorie - today the Stichtagsauskunft "who
 * reached this object" (#1822, ADR-0036 Entscheidung 8).
 *
 * <p><b>Why it is not in {@code io.opaa.audit}.</b> The answer composes three fachpakete - {@code
 * io.opaa.permission} (grants and group memberships), {@code io.opaa.library} (the
 * organization-wide release) and {@code io.opaa.space} (space memberships) - and all three already
 * depend on {@code io.opaa.audit} to write their events. Putting the composition there would make
 * every one of those edges a cycle. This package sits above them all and nothing depends on it but
 * {@code io.opaa.api}; {@code PermissionPackageBoundaryTest} holds that direction.
 *
 * <p>The bar of every revision access - the AUDITOR role, the mandatory Anlass, the bounded window
 * and the bounded paging - stays in {@code io.opaa.audit.AuditAccessGate}, shared with the audit
 * log funnel, so both holdings are opened under one rule.
 */
package io.opaa.revision;
