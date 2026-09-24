package io.opaa.asset;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.permission.AssetType;

/**
 * What an asset type declares about itself so the shell can serve it: its key, the audit object it
 * appears as, and the words messages use for it. The business package owning the type contributes
 * one bean; the shell never names a type.
 *
 * <p>{@link #requireListedWithinLimits} and {@link #requireAllAccountsGrantAllowed} are the two
 * ports for type-specific <b>restrictions</b>, one per thing that can be capped: the shell asks the
 * first before it applies a requested findability, the grant service the second before it writes a
 * grant to "Alle Konten". A type that restricts nothing keeps the defaults. Everything else -
 * grants, derivation, history, succession - is the shell's.
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
   * Refuses a findability the type does not allow for this asset, with a {@code 409} naming the
   * limit. Called with the requested state before it is applied.
   */
  default void requireListedWithinLimits(Asset asset, boolean listed) {}

  /**
   * Refuses a grant to "Alle Konten" the type does not allow for this asset, with a {@code 409}
   * naming the limit (#1931, ADR-0037 Entscheidung 5). Called before the grant is written - this is
   * where a connector library's share cap takes effect now that organization-wide reach is a grant
   * and no longer a field of the shell.
   */
  default void requireAllAccountsGrantAllowed(Asset asset) {}
}
