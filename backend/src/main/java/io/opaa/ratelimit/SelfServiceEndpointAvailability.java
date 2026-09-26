package io.opaa.ratelimit;

/**
 * Whether the two switchable self-service endpoints of the local sign-in are served right now. A
 * rule for an endpoint nobody serves is not counted, so a switched-off flow is answered exactly
 * like an unknown route. Asked on every request; {@link #NONE} applies wherever no implementing
 * bean exists and reports both endpoints as absent.
 */
public interface SelfServiceEndpointAvailability {

  /** Neither endpoint is served. */
  SelfServiceEndpointAvailability NONE =
      new SelfServiceEndpointAvailability() {

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
