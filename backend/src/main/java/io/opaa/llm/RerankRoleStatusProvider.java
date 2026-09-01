package io.opaa.llm;

/**
 * The single, narrow contract between the rerank model role (#1050) and everything that only wants
 * to know how that role is doing - today the administration page "Suche &amp; Indexierung" (#1053).
 *
 * <p>Implementations answer from configuration and the last known endpoint state; the call is on
 * the path of an administrative page load and must not block on a fresh network round trip of its
 * own. Until #1050 ships its own implementation, {@link RerankRoleConfiguration} contributes one
 * that reports the role as switched off, or as a Störung when the switch is on without a role being
 * built yet.
 */
public interface RerankRoleStatusProvider {

  RerankRoleStatus currentStatus();
}
