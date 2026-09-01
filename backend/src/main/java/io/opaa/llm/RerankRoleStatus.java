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
 */
public record RerankRoleStatus(
    RerankRoleState state, String baseUrl, String modelIdentifier, String diagnostic) {

  /** The role's switch is off. */
  public static RerankRoleStatus disabled() {
    return new RerankRoleStatus(RerankRoleState.DISABLED, null, null, null);
  }
}
