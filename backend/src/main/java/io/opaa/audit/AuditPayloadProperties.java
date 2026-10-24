package io.opaa.audit;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How {@link AuditLogService} treats a payload key {@link AuditEventCatalog} does not list for its
 * event type: dropped before writing and logged as an error, or the write refused. The {@code dev}
 * profile, and with it every test, refuses, so that a key a writer adds without listing it fails.
 *
 * @param rejectUnlisted refuse the write instead of dropping the unlisted keys, {@code false} when
 *     unset
 */
@ConfigurationProperties(prefix = "opaa.audit.payload")
@InstallationWide
public record AuditPayloadProperties(
    @NoDataImpact("a write check of the audit payload; either way no unlisted key is stored")
        boolean rejectUnlisted) {}
