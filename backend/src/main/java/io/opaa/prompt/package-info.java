/**
 * The prompt library, the second asset type on the shell
 * (docs/features/spaces-and-assets.md#prompt-bibliothek): a type table, an entity extending {@code
 * io.opaa.asset.Asset}, an {@code io.opaa.asset.AssetTypeDefinition} - and its prompts with their
 * variables.
 *
 * <p><b>Dependencies.</b> This package builds on {@code io.opaa.asset} and asks {@code
 * io.opaa.permission} for roles only; it holds no grant, release, ownership or succession logic of
 * its own. It does not know {@code io.opaa.library}, and no business package knows it - {@code
 * io.opaa.permission.PermissionPackageBoundaryTest} holds both directions. The query path reaches
 * it from above, to check a prompt inserted in the chat ({@link io.opaa.prompt.PromptService}).
 */
package io.opaa.prompt;
