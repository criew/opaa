package io.opaa.group;

import java.util.List;

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
 * @param responsible whom to ask about a protected group instead of reading its member list
 *     (ADR-0036, Entscheidung 9): its stewards where it is internal, its contact points where it
 *     comes from a provider (#1875). Empty for every unprotected group - there the member list is
 *     the answer, and naming people here would be a disclosure nobody asked for.
 */
public record SelectableGroup(
    Group group,
    String name,
    List<String> responsible,
    GroupProviderView provider,
    Integer activeMemberCount,
    boolean smallGroup,
    boolean emptyGroup,
    boolean selectable,
    boolean dissolved,
    boolean providerDisabled,
    boolean unmaintained) {}
