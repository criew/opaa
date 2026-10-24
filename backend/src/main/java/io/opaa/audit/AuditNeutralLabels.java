package io.opaa.audit;

import java.util.List;

/**
 * The object labels a protocol entry carries for an object that belongs to exactly one person: its
 * own name would be its owner's. An entry with one of them names its object by id only, and the
 * SIEM export (ADR-0055, Entscheidung 2) sends neither that id nor the label.
 */
public final class AuditNeutralLabels {

  /** An owner-only asset: a private knowledge or prompt library. */
  public static final String PRIVATE_ASSET = "Private Bibliothek";

  /** The personal space every account has. */
  public static final String PERSONAL_SPACE = "Persönlicher Bereich";

  /** A prompt in a private prompt library. */
  public static final String PRIVATE_PROMPT = "Privater Prompt";

  public static final List<String> ALL = List.of(PRIVATE_ASSET, PERSONAL_SPACE, PRIVATE_PROMPT);

  private AuditNeutralLabels() {}
}
