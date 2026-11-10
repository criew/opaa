package io.opaa.query.toolloop;

/**
 * A tool as one run offers it to the model.
 *
 * @param description what the model decides by; written by the application, never by an agent.
 * @param inputSchema the JSON schema of the arguments.
 */
public record ToolOffer(String description, String inputSchema) {}
