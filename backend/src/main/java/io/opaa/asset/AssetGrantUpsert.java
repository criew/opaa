package io.opaa.asset;

import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Parameters for {@link AssetGrantService#upsertGrant} - replaces the generated {@code
 * AssetGrantRequest} at the service boundary (#860): domain services do not know {@code
 * io.opaa.api.dto} types, see AGENTS.md "API &amp; DTO-Konvention". Immutable, fluent {@code
 * expiresAt} setter mirrors {@code AssetGrantRequest}'s generated builder for a low-friction test
 * call site.
 *
 * @param subjectId {@code null} exactly for {@link AssetGrantSubjectType#ALL_ACCOUNTS}, which names
 *     no row.
 */
public record AssetGrantUpsert(
    AssetGrantSubjectType subjectType, UUID subjectId, AssetRole role, Instant expiresAt) {

  public AssetGrantUpsert(AssetGrantSubjectType subjectType, UUID subjectId, AssetRole role) {
    this(subjectType, subjectId, role, null);
  }

  /** A grant to "Alle Konten" - the subject that names no row. */
  public static AssetGrantUpsert forAllAccounts(AssetRole role) {
    return new AssetGrantUpsert(AssetGrantSubjectType.ALL_ACCOUNTS, null, role, null);
  }

  public AssetGrantUpsert expiresAt(Instant expiresAt) {
    return new AssetGrantUpsert(subjectType, subjectId, role, expiresAt);
  }
}
