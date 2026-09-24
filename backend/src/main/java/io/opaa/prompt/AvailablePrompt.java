package io.opaa.prompt;

/**
 * One entry of the chat's slash-command selection: a prompt of a library the caller may read.
 *
 * @param associatedWithSpace whether the library is associated with the chat's space.
 */
public record AvailablePrompt(Prompt prompt, PromptLibrary library, boolean associatedWithSpace) {}
