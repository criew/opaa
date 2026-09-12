package io.opaa.auth.local;

import io.opaa.api.types.SystemRole;
import java.time.Instant;

/**
 * What an administrator provides for a new local account (ADR-0033, Entscheidung 11).
 *
 * @param systemRole {@code null} for {@code USER}; anything else is granted through {@code
 *     UserService#updateRole}
 * @param expiresAt the expiry date, or {@code null} for the default of {@code
 *     local_auth_settings.default_expiry_days} - unless {@code noExpiry}
 * @param noExpiry create the account without an expiry date; an explicit act, never the default
 * @param invite {@code true} for an invitation link, {@code false} for a generated initial password
 */
public record LocalUserCreation(
    String email,
    String displayName,
    SystemRole systemRole,
    Instant expiresAt,
    boolean noExpiry,
    String createdReason,
    boolean invite) {}
