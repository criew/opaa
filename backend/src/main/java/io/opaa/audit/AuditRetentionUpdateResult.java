package io.opaa.audit;

/**
 * The outcome of {@link AuditRetentionSettingsService#updateRetention} - the new value always takes
 * effect (validation already rejected anything out of bounds before this is constructed). {@code
 * inconsistentWithContentRetention} is currently always {@code false}: the content-retention
 * cross-check it was meant to report has no data source (see {@link
 * AuditRetentionSettingsService#updateRetention}'s own comment).
 */
public record AuditRetentionUpdateResult(
    int retentionMonths, boolean inconsistentWithContentRetention) {}
