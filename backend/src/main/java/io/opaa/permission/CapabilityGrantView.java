package io.opaa.permission;

/**
 * One {@link CapabilityGrant} together with the display name of its subject - the enriched view the
 * administration overview needs (AGENTS.md, "API &amp; DTO-Konvention": a response that is more
 * than the entity carries its extra field in a domain record, not in a DTO the service knows).
 *
 * @param subjectName {@code null} for {@code ALL_ACCOUNTS}, which names no subject, and for a
 *     subject whose row is no longer resolvable
 */
public record CapabilityGrantView(CapabilityGrant grant, String subjectName) {}
