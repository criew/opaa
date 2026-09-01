package io.opaa.llm;

import java.time.Instant;

/**
 * The continuously queryable state of the rerank model role - the named backend query the
 * administration surface (issue #1053) reads, and the answer to "a message that only appears at
 * startup is out of anyone's reach a day later" (docs/features/hybrid-retrieval.md, Arbeitspaket
 * 4).
 *
 * <p><b>Carries no access key, in no form.</b> Not the key, not a truncation of it, not a hash: the
 * only fields describing the configuration are the endpoint address and the model identifier.
 *
 * @param state what the role is currently in; {@link RerankRoleState#contradictsIntent()} says
 *     whether it needs anyone's attention.
 * @param enabled the raw switch value, so a consumer can distinguish "off" from "on but broken"
 *     without interpreting {@link #state}.
 * @param baseUrl configured endpoint address, empty when the role is unbound.
 * @param model configured model identifier, empty when the role is unbound.
 * @param message one German sentence stating what holds and, where something is wrong, what to
 *     check. User-facing text, unlike the technical strings of the retrieval explanation protocol.
 * @param checkedAt when the endpoint was last probed; {@code null} when no probe has run because
 *     the role is switched off or unbound.
 */
public record RerankRoleStatus(
    RerankRoleState state,
    boolean enabled,
    String baseUrl,
    String model,
    String message,
    Instant checkedAt) {

  public RerankRoleStatus {
    baseUrl = baseUrl == null ? "" : baseUrl;
    model = model == null ? "" : model;
  }

  /** Whether a query may actually call the rerank endpoint. */
  public boolean usable() {
    return state == RerankRoleState.ACTIVE;
  }
}
