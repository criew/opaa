package io.opaa.asset;

import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.permission.AssetType;

/**
 * What an asset type declares about itself so the shell can serve it: its key, the audit object it
 * appears as, and the words messages use for it. The business package owning the type contributes
 * one bean; the shell never names a type.
 *
 * <p>{@link #requireReachWithinLimits} is the one port for type-specific <b>restrictions</b>: the
 * shell asks it before it applies a requested reach, and a type that restricts nothing keeps the
 * default. Everything else - grants, derivation, history, succession - is the shell's.
 */
public interface AssetTypeDefinition {

  AssetType assetType();

  /** The object an audit entry about an asset of this type names. */
  AuditObjectType auditObjectType();

  /** The audit event the creation of an asset of this type writes. */
  AuditEventType createdAuditEventType();

  /** The singular noun, feminine or neuter with "die"/"diese" ("Bibliothek"). */
  String singular();

  /** The plural noun ("Bibliotheken"). */
  String plural();

  /**
   * Refuses a reach the type does not allow for this asset, with a {@code 409} naming the limit.
   * Called with the complete requested state before it is applied.
   */
  default void requireReachWithinLimits(Asset asset, AssetVisibility visibility, boolean listed) {}
}
