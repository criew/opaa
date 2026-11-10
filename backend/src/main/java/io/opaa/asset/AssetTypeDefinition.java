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

  /** The singular noun in the nominative ("Bibliothek", "Agent"). */
  String singular();

  /**
   * Whether the noun is masculine ("der Agent"); the phrases below then take its oblique form.
   * Every other type is feminine ("die Bibliothek").
   */
  default boolean masculine() {
    return false;
  }

  /** The singular noun outside the nominative ("Agenten"); the nominative for a feminine noun. */
  default String oblique() {
    return singular();
  }

  /** "diese Bibliothek" / "dieser Agent", the subject of a sentence. */
  default String thisNominative() {
    return (masculine() ? "dieser " : "diese ") + singular();
  }

  /** "diese Bibliothek" / "diesen Agenten", after "auf" or "für". */
  default String thisAccusative() {
    return (masculine() ? "diesen " : "diese ") + oblique();
  }

  /** "eine Bibliothek" / "einen Agenten", the object of "anlegen". */
  default String aAccusative() {
    return (masculine() ? "einen " : "eine ") + oblique();
  }

  /** "einer Bibliothek" / "eines Agenten". */
  default String aGenitive() {
    return (masculine() ? "eines " : "einer ") + oblique();
  }

  /** "der Bibliothek" / "des Agenten". */
  default String theGenitive() {
    return (masculine() ? "des " : "der ") + oblique();
  }

  /** "die Bibliothek" / "der Agent", the subject of a sentence. */
  default String theNominative() {
    return (masculine() ? "der " : "die ") + singular();
  }

  /** "Ihre Bibliothek" / "Ihr Agent", the subject of a sentence. */
  default String yourNominative() {
    return (masculine() ? "Ihr " : "Ihre ") + singular();
  }

  /** The phrase with its first letter in upper case, for the start of a sentence. */
  static String capitalized(String phrase) {
    return phrase.isEmpty()
        ? phrase
        : Character.toUpperCase(phrase.charAt(0)) + phrase.substring(1);
  }

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
