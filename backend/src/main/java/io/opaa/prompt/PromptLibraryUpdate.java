package io.opaa.prompt;

import io.opaa.api.types.AssetVisibility;

/** The complete new state of a prompt library's name, description and reach. */
public record PromptLibraryUpdate(
    String name, String description, AssetVisibility visibility, boolean listed) {}
