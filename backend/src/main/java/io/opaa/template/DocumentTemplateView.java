package io.opaa.template;

import io.opaa.api.types.AssetRole;
import io.opaa.permission.AssetReach;
import io.opaa.permission.SuccessionFinding;

/**
 * A document template as the caller sees it.
 *
 * @param ownerName {@code null} when the owner has no name the caller may see.
 * @param succession {@code null} while the template has a capable owner.
 */
public record DocumentTemplateView(
    DocumentTemplate template,
    AssetRole myRole,
    String ownerName,
    SuccessionFinding succession,
    AssetReach reach) {}
