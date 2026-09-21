package io.opaa.space;

import io.opaa.api.types.SpaceRole;
import io.opaa.permission.AccessPath;
import java.util.List;
import java.util.UUID;

/**
 * Why one person reaches one space (#1822, ADR-0036 Entscheidung 9): the effective role and every
 * way to it. {@code pathsWithheld} is set when a way runs through a protected group and the answer
 * is about somebody else - the role is then stated without the group, because in a space with one
 * protected group the naming would follow by elimination. An own derivation never withholds.
 */
public record SpaceAccessDerivation(
    UUID spaceId,
    UUID userId,
    SpaceRole effectiveRole,
    List<AccessPath> paths,
    boolean pathsWithheld) {}
