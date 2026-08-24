package io.opaa.library;

import io.opaa.group.PermissionSubjectType;
import java.time.Instant;
import java.util.UUID;

/**
 * Parameters for {@link AssetGrantService#upsertGrant} - replaces the generated {@code
 * AssetGrantRequest} at the service boundary (#860): domain services do not know {@code
 * io.opaa.api.dto} types, see AGENTS.md "API & DTO-Konvention". Immutable, fluent {@code expiresAt}
 * setter mirrors {@code AssetGrantRequest}'s generated builder for a low-friction test call site.
 */
public record AssetGrantUpsert(
    PermissionSubjectType subjectType, UUID subjectId, AssetRole role, Instant expiresAt) {

  public AssetGrantUpsert(PermissionSubjectType subjectType, UUID subjectId, AssetRole role) {
    this(subjectType, subjectId, role, null);
  }

  public AssetGrantUpsert expiresAt(Instant expiresAt) {
    return new AssetGrantUpsert(subjectType, subjectId, role, expiresAt);
  }
}
