package io.opaa.audit;

/**
 * The outcome of {@link AuditRetentionSettingsService#updateRetention} - the new value always takes
 * effect (validation already rejected anything out of bounds before this is constructed); {@code
 * inconsistentWithContentRetention} only flags whether the specification's cross-check fired
 * (docs/features/security-and-compliance.md#aufbewahrung), it never blocks the change.
 */
public record AuditRetentionUpdateResult(
    int retentionMonths, boolean inconsistentWithContentRetention) {}
