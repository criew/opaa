/**
 * The lifecycle of ownership and responsibility (#1819, ADR-0036 Entscheidung 6): the derived state
 * "Nachfolge offen", the detection run that records when it began and ended, and the operational
 * list with its three tabs.
 *
 * <p><b>Why it is not in {@code io.opaa.permission}.</b> The findings compose three fachpakete -
 * {@code io.opaa.library} (an asset's owner), {@code io.opaa.space} (a space's capable ADMIN) and
 * {@code io.opaa.group} (a group's stewards and its effect) - and all three already depend on
 * {@code io.opaa.permission}. Putting the composition there would make every one of those edges a
 * cycle. This package sits above them; each of them contributes a {@link
 * io.opaa.permission.SuccessionFindingSource} for its own object type, and a further asset type
 * (#1726) adds a bean rather than a branch.
 *
 * <p><b>The state itself is never stored.</b> {@code succession_cases} carries only what cannot be
 * derived - when a state was first seen and when it ended; everything else is asked of the sources
 * on every read, so a state that ends by itself disappears from the list by itself.
 */
package io.opaa.succession;
