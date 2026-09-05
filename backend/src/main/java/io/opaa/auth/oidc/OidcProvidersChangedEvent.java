package io.opaa.auth.oidc;

/**
 * Published by {@link OidcProviderService} inside the transaction of every provider change (#1329,
 * ADR-0025 Entscheidung 3). {@link OidcProviderRegistry} listens {@code AFTER_COMMIT}, so a
 * rolled-back change never rebuilds the registry from state that was never durable - the same
 * contract {@code ActiveChatModelChangedEvent} has with {@code ActiveChatModelResolver}.
 */
public record OidcProvidersChangedEvent() {}
