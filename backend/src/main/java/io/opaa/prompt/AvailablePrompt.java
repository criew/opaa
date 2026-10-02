package io.opaa.prompt;

/**
 * One entry of the chat's slash-command selection: a prompt of a library that is associated with
 * the chat's space and readable by the caller.
 */
public record AvailablePrompt(Prompt prompt, PromptLibrary library) {}
