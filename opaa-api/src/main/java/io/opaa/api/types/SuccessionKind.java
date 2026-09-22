package io.opaa.api.types;

/**
 * The three findings of the operational list (ADR-0036, Entscheidung 6) - one mechanic, three tabs:
 * the same detection run, the same age, the same object-bound entry point and the same
 * Sichtungsvermerk.
 */
public enum SuccessionKind {

  /** An object without a capable responsible party - an asset, a space or an internal group. */
  OPEN_SUCCESSION,

  /**
   * An effective group that holds grants or is a space member and has no active account left: its
   * releases reach nobody. The visible signal for the unprotected token path - after a renamed
   * claim, 40 releases are dead and nothing else shows it.
   */
  GRANTS_WITHOUT_RECIPIENT,

  /**
   * An internal group without grant, without space membership, without ownership and without an
   * active member. Wildwuchs is made visible, not prevented; deleting stays an action.
   */
  GROUP_WITHOUT_EFFECT
}
