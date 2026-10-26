package io.opaa.llm;

/**
 * The single, narrow contract between the rerank model role and everything that only wants to know
 * how that role is doing - today the administration pages "Suche &amp; Indexierung" and "Modelle".
 *
 * <p>Implementations answer from the stored endpoint and the last known endpoint state; the call is
 * on the path of an administrative page load and must not block on a fresh network round trip of
 * its own. The one implementation is {@link RerankModelRole}.
 */
public interface RerankRoleStatusProvider {

  RerankRoleStatus currentStatus();
}
