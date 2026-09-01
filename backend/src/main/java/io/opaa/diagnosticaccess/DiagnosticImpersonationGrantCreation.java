package io.opaa.diagnosticaccess;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain parameters for granting the "Sicht als" befugnis. Both {@code scopeGroupId} and {@code
 * validUntil} are non-optional by construction - there is no variant of this record that could
 * express a scopeless or unbounded grant.
 */
public record DiagnosticImpersonationGrantCreation(
    UUID holderUserId, UUID scopeGroupId, Instant validFrom, Instant validUntil) {}
