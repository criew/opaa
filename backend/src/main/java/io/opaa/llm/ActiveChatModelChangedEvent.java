package io.opaa.llm;

/**
 * Published by {@link LlmModelService} whenever a change may affect which {@link LlmModel} is
 * systemwide active - activation, or an update to the currently active row (#758). Carries no
 * payload: {@link ActiveChatModelResolver} reacts by dropping its cached client and re-reading
 * {@code llm_models} on the next call, rather than trying to compute the new state from the event
 * itself.
 */
final class ActiveChatModelChangedEvent {}
