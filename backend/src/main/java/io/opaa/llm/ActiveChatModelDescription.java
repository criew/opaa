package io.opaa.llm;

/**
 * The two user-facing facts about the currently active chat model {@code
 * io.opaa.observability.ChatHealthIndicator} shows (#758) - deliberately not the whole {@link
 * LlmModel}: a health check has no business exposing temperature, max tokens or whether an access
 * key is configured.
 */
public record ActiveChatModelDescription(String baseUrl, String modelIdentifier) {}
