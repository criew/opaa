package io.opaa.prompt;

/** The complete new state of a prompt library's name, description and findability. */
public record PromptLibraryUpdate(String name, String description, boolean listed) {}
