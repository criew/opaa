package io.opaa.llm;

/**
 * The continuously readable state of an optional model role, as the administration pages read it.
 * Carries no access key in any form - a status display shows it neither in full nor shortened.
 *
 * @param baseUrl the configured address, {@code null} while unset or rejected for carrying
 *     credentials.
 * @param modelIdentifier the configured model, {@code null} while unset or for a role connected
 *     without one.
 * @param diagnostic a short technical English statement, never containing content sent to the
 *     endpoint or its response body; {@code null} when there is nothing beyond {@code state}. Not a
 *     user-facing text: the German wording is the API mapper's business.
 * @param timedOut whether the last known failure was the endpoint not answering within the role's
 *     timeout - as opposed to the connection itself failing (refused, unknown host, TLS). Always
 *     {@code false} outside {@link OptionalRoleState#UNREACHABLE}.
 * @param baseUrlRejected whether the configured address was refused for carrying credentials. Only
 *     ever {@code true} for {@link OptionalRoleState#UNCONFIGURED}.
 */
public record OptionalRoleStatus(
    OptionalRoleState state,
    String baseUrl,
    String modelIdentifier,
    String diagnostic,
    boolean timedOut,
    boolean baseUrlRejected) {

  /** Convenience for every state but a rejected base address. */
  public OptionalRoleStatus(
      OptionalRoleState state,
      String baseUrl,
      String modelIdentifier,
      String diagnostic,
      boolean timedOut) {
    this(state, baseUrl, modelIdentifier, diagnostic, timedOut, false);
  }

  /** Convenience for every state but a timed-out {@link OptionalRoleState#UNREACHABLE}. */
  public OptionalRoleStatus(
      OptionalRoleState state, String baseUrl, String modelIdentifier, String diagnostic) {
    this(state, baseUrl, modelIdentifier, diagnostic, false);
  }

  /** The role is switched off. */
  public static OptionalRoleStatus disabled() {
    return new OptionalRoleStatus(OptionalRoleState.DISABLED, null, null, null);
  }
}
