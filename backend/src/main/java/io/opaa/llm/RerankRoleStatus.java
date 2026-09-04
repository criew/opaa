package io.opaa.llm;

/**
 * The continuously queryable state of the rerank model role (#1050 acceptance criterion 3), as the
 * administration page reads it.
 *
 * <p>{@code baseUrl} and {@code modelIdentifier} are {@code null} while the role is unbelegt. The
 * access key is not part of this type and never will be - a status display shows it neither in full
 * nor shortened.
 *
 * @param diagnostic a short technical statement of what the endpoint reported, or {@code null} when
 *     there is nothing beyond {@code state} to say. Not a user-facing text: the German wording is
 *     the API mapper's business.
 * @param timedOut whether the last known failure was the endpoint not answering within {@code
 *     opaa.rerank.timeout} - as opposed to the connection itself failing (refused, unknown host,
 *     TLS). A reachable-but-slow CPU reranker must read differently from a genuinely unreachable
 *     one on the state page (#1154), even though {@code state} is {@link
 *     RerankRoleState#UNREACHABLE} for both. Always {@code false} outside that state.
 * @param baseUrlRejected whether the configured base address was refused because it carried
 *     credentials (#1147). Distinguishes "nothing was configured" from "what was configured cannot
 *     be used" - two different remedies - while {@code baseUrl} stays {@code null} either way,
 *     since a rejected address may not be shown. Only ever {@code true} for {@link
 *     RerankRoleState#UNCONFIGURED}.
 */
public record RerankRoleStatus(
    RerankRoleState state,
    String baseUrl,
    String modelIdentifier,
    String diagnostic,
    boolean timedOut,
    boolean baseUrlRejected) {

  /** Convenience for every state but a rejected base address. */
  public RerankRoleStatus(
      RerankRoleState state,
      String baseUrl,
      String modelIdentifier,
      String diagnostic,
      boolean timedOut) {
    this(state, baseUrl, modelIdentifier, diagnostic, timedOut, false);
  }

  /** Convenience for every state but a timed-out {@link RerankRoleState#UNREACHABLE}. */
  public RerankRoleStatus(
      RerankRoleState state, String baseUrl, String modelIdentifier, String diagnostic) {
    this(state, baseUrl, modelIdentifier, diagnostic, false);
  }

  /** The role's switch is off. */
  public static RerankRoleStatus disabled() {
    return new RerankRoleStatus(RerankRoleState.DISABLED, null, null, null);
  }
}
