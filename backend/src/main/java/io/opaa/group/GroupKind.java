package io.opaa.group;

/**
 * What a group is for and where its membership comes from.
 *
 * <ul>
 *   <li>{@link #ORG_UNIT} - synchronised from the directory (department, division, agency). Carries
 *       a parent unit and can have curators bound to it (see #208). Membership is never invented
 *       here - it is exactly what the directory places in the group (see #237).
 *   <li>{@link #AD_HOC} - created in the system by an administrator (e.g. "Projektbeteiligte
 *       Phoenix"). Has no curator and does not carry a distribution level of its own.
 * </ul>
 *
 * <p>Curator approval for a grant is bound to the group's <em>reach</em> (its member count), not to
 * its kind - a large AD_HOC group requires the same approval as an ORG_UNIT group of comparable
 * size. That policy is implemented where grants are created (see #202, #208) and does not live on
 * this enum.
 */
public enum GroupKind {
  ORG_UNIT,
  AD_HOC
}
