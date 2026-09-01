package io.opaa.library;

/**
 * A {@link AssetGrant} enriched with the subject's and granter's display names, resolved by {@link
 * AssetGrantService#toViews} (#423) - the domain counterpart of the generated {@code
 * AssetGrantResponse}, mapped onto it by {@code io.opaa.api.AssetGrantResponseMapper}.
 *
 * @param subjectDisplayName {@code null} only if the subject row itself no longer exists.
 * @param grantedByDisplayName display name of whoever conferred the grant's current role (see
 *     {@link AssetGrant#updateRole}); {@code null} when the grant carries no {@code
 *     grantedByUserId} (a historical row from before that field existed) or that user no longer
 *     exists.
 */
public record AssetGrantView(
    AssetGrant grant, String subjectDisplayName, String grantedByDisplayName) {}
