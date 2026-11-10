package io.opaa.agent;

/** The complete new state of an agent: name, purpose and what a run uses. */
public record AgentUpdate(String name, String description, AgentConfiguration configuration) {}
