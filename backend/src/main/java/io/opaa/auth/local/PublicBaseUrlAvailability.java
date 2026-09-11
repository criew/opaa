package io.opaa.auth.local;

/**
 * Whether the installation's public base URL ({@code OPAA_PUBLIC_BASE_URL}, ADR-0033 Entscheidung
 * 10) is configured - the precondition of every flow that hands out a link (self-registration,
 * password reset). The mail sub-system provides the real bean; without one, {@link #NONE} applies
 * and {@code GET /api/v1/auth/config} reports both flows as unavailable.
 */
@FunctionalInterface
public interface PublicBaseUrlAvailability {

  PublicBaseUrlAvailability NONE = () -> false;

  boolean isConfigured();
}
