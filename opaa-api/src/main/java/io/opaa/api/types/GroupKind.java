package io.opaa.api.types;

/**
 * What a group is for and where its membership comes from.
 *
 * <ul>
 *   <li>{@link #ORG_UNIT} - synchronised from the directory (department, division, agency). Carries
 *       a parent unit and can have curators bound to it (see #208). Membership is never invented
 *       here - it is exactly what the directory places in the group (see #237).
 *   <li>{@link #AD_HOC} - created in the system by an administrator (e.g. "Projektbeteiligte
 *       Phoenix"). Has no curator and does not carry a distribution level of its own.
 *   <li>{@link #IDENTITY_PROVIDER} - the groups claim of a provider's tokens (ADR-0025,
 *       Entscheidung 4): membership is refreshed on every sign-in, the {@code external_id} is
 *       namespaced per provider ({@code oidc:<provider-id>:<name>}), and the group is read-only in
 *       the group management, never a directory-sync subject and never a "Sicht als" scope.
 * </ul>
 *
 * <p>Curator approval for a grant is bound to the group's <em>reach</em> (its member count), not to
 * its kind - a large AD_HOC group requires the same approval as an ORG_UNIT group of comparable
 * size. That policy is implemented where grants are created (see #202, #208) and does not live on
 * this enum.
 */
public enum GroupKind {
  ORG_UNIT,
  AD_HOC,
  IDENTITY_PROVIDER
}
