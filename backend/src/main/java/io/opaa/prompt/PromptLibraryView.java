package io.opaa.prompt;

import io.opaa.api.types.AssetRole;
import io.opaa.permission.SuccessionFinding;

/**
 * A prompt library as the caller sees it.
 *
 * @param ownerName {@code null} when the owner has no name the caller may see.
 * @param succession {@code null} while the library has a capable owner.
 */
public record PromptLibraryView(
    PromptLibrary library,
    AssetRole myRole,
    long promptCount,
    String ownerName,
    SuccessionFinding succession) {}
