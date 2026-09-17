package io.opaa.auth.local;

/**
 * Whether the two switchable self-service flows exist right now (ADR-0033, Entscheidung 11) - the
 * same answer {@code GET /api/v1/auth/config} publishes as {@code passwordResetEnabled} and {@code
 * selfRegistrationEnabled}, which stays the only channel through which the state is readable
 * (#1592).
 *
 * <p>Two decisions outside the flows themselves need the answer before any handler runs, so that a
 * switched-off flow is answered exactly like an unknown route: the authorization rules of {@code
 * OidcSecurityConfig} (which let the request fall through to {@code /api/**} authenticated) and the
 * path rules of {@code RateLimitConfiguration} (which do not count a path nobody serves). {@link
 * #NONE} applies wherever the implementing bean is absent - the {@code @WebMvcTest} slices that
 * import the chain to assert its public paths - and reports both flows as absent.
 */
public interface LocalSelfServiceAvailability {

  /** Neither flow exists; both paths are then authenticated like any other unknown route. */
  LocalSelfServiceAvailability NONE =
      new LocalSelfServiceAvailability() {

        @Override
        public boolean isPasswordResetAvailable() {
          return false;
        }

        @Override
        public boolean isSelfRegistrationAvailable() {
          return false;
        }
      };

  /** Whether {@code POST /api/v1/auth/local/forgot-password} is served. */
  boolean isPasswordResetAvailable();

  /** Whether {@code POST /api/v1/auth/local/register} is served. */
  boolean isSelfRegistrationAvailable();
}
