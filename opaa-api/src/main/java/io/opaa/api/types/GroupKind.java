package io.opaa.api.types;

/**
 * What a group is for, where its membership comes from, and who may edit it. The kind never decides
 * what a grant to the group is worth - a grant is issued by an asset's {@code MANAGER} alone, for
 * every kind alike, and needs neither approval from the group's side nor stays below a size
 * threshold (docs/features/spaces-and-assets.md#freigabe-an-eine-gruppe-braucht-keine-zustimmung).
 *
 * <ul>
 *   <li>{@link #ORG_UNIT} - synchronised from the directory (department, division, agency), the
 *       only kind directory synchronisation creates, renames or dissolves. Carries the parent unit
 *       the directory reports, never for inherited membership: a group holds exactly the members
 *       the directory places in it. Not editable in the group management, and admissible as the
 *       scope of a "Sicht als" befugnis - like every provider group since #1879.
 *   <li>{@link #AD_HOC} - created in the group management (e.g. "Projektbeteiligte Phoenix"), the
 *       only kind editable there. Has no directory counterpart and therefore no {@code external_id}
 *       and no parent unit.
 *   <li>{@link #IDENTITY_PROVIDER} - the groups claim of a provider's tokens (ADR-0025,
 *       Entscheidung 4): membership is refreshed on every sign-in, the {@code external_id} is
 *       namespaced per provider ({@code oidc:<provider-id>:<name>}), and the group is read-only in
 *       the group management and never a directory-sync subject. It <b>is</b> admissible as the
 *       scope of a "Sicht als" befugnis (#1879): a house that stays in token mode has no other kind
 *       to name.
 * </ul>
 */
public enum GroupKind {
  ORG_UNIT,
  AD_HOC,
  IDENTITY_PROVIDER
}
