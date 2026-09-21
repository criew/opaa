package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.permission.AccessPath;
import java.util.List;
import java.util.UUID;

/**
 * Why one person reaches one library (#1822, ADR-0036 Entscheidung 9): the effective role and every
 * own way to it. Always about the asking person - the library derivation knows no third party, so
 * nothing here is ever withheld.
 */
public record LibraryAccessDerivation(
    UUID libraryId, AssetRole effectiveRole, List<AccessPath> paths) {}
