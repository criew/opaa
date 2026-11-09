package io.opaa.asset;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.permission.AssetType;

/**
 * What an asset type declares about itself so the shell can serve it: its key, the audit object it
 * appears as, and the words messages use for it. The business package owning the type contributes
 * one bean; the shell never names a type.
 *
 * <p>Everything else - grants, derivation, history, succession - is the shell's.
 *
 * <p>{@link #enabled} switches a type off for its readers (ADR-0044): an inactive type stays
 * registered, so ownership, history, succession and audit keep serving its existing assets, but no
 * read interface lists it and a request naming it answers like an unknown asset.
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
   * Whether the type is offered to readers right now; asked on every read, never cached. A type
   * behind a switched-off feature answers {@code false}.
   */
  default boolean enabled() {
    return true;
  }
}
