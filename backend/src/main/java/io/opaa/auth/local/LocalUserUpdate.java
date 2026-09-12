package io.opaa.auth.local;

import io.opaa.api.types.SystemRole;
import java.time.Instant;

/**
 * A partial change of a local account: every {@code null} field stays as it is; {@code noExpiry}
 * removes the expiry date and wins over {@code expiresAt}.
 */
public record LocalUserUpdate(
    String email,
    String displayName,
    SystemRole systemRole,
    Instant expiresAt,
    boolean noExpiry,
    String createdReason) {}
