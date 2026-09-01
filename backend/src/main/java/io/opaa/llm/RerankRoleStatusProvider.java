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
 *
 * <p><b>The real implementation registers itself as a stereotype bean</b> - {@code @Component} or
 * {@code @Service} on the implementing class, unconditionally. A {@code @Bean} method in a second
 * component-scanned {@code @Configuration} is not equivalent: the fallback's
 * {@code @ConditionalOnMissingBean} may be evaluated first and then both beans exist, which fails
 * the context start with {@code NoUniqueBeanDefinitionException}. Making the real provider itself
 * conditional is worse still, because a condition that does not hold leaves the fallback reporting
 * a reassuring "ausdrücklich abgeschaltet" for a role that was meant to be on.
 */
public interface RerankRoleStatusProvider {

  RerankRoleStatus currentStatus();
}
