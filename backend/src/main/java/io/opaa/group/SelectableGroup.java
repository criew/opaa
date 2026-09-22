package io.opaa.group;

/**
 * One group as the Subjekt-Auswahl of ADR-0036, Entscheidung 9 shows it (#1820): name, origin and
 * the number of active accounts - never the members. The "kleine Gruppe" suppression is already
 * applied here, so no caller can hand out a figure this record withheld.
 *
 * @param name the name this answer may show: null for a protected group resolved by its id, where
 *     the caller did not name it and a name handed back would undo its namelessness.
 * @param activeMemberCount null when {@code smallGroup} is true and null for a protected group,
 *     where the size is the actual disclosure.
 * @param selectable false for a group that may hold what it holds but must not become a new grant
 *     target or space member; {@code dissolved}, {@code providerDisabled} and {@code unmaintained}
 *     name which reason applies.
 */
public record SelectableGroup(
    Group group,
    String name,
    GroupProviderView provider,
    Integer activeMemberCount,
    boolean smallGroup,
    boolean emptyGroup,
    boolean selectable,
    boolean dissolved,
    boolean providerDisabled,
    boolean unmaintained) {}
