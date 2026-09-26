package io.opaa.group.sync;

/**
 * One group a sync run created, renamed, dissolved or stopped maintaining. {@code previousName} is
 * only set for a rename.
 *
 * <p>{@code memberCount} is what ADR-0036, Entscheidung 3 asks the report to show before anything
 * is applied: for a group the run would <b>create</b>, the number of <em>direct</em> members the
 * directory reports for it - a Keycloak department that only holds subgroups arrives here as a
 * zero, which is exactly the surprise an operator must see beforehand. For a group that already
 * exists (rename, dissolution, no longer maintained) it is the number of memberships it currently
 * holds, i.e. what stays in force.
 *
 * <p>Domain counterpart of the generated {@code DirectorySyncGroupChange}, mapped by {@code
 * DirectorySyncResponseMapper}.
 */
public record GroupChange(
    String externalId, String name, String previousName, String sourcePath, int memberCount) {}
