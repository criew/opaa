package io.opaa.api.types;

/**
 * An installation-wide right to <em>create</em> something, granted to accounts, groups or to all
 * accounts at once (ADR-0036, Entscheidung 5; "Anlegerecht" in the user interface). A capability
 * opens a creation path and never an existing content: no value of this enum may widen what {@code
 * LibraryAccessService#readableLibraryIds} returns.
 *
 * <p>A closed vocabulary, mirrored by the database check constraints {@code
 * chk_capability_grants_capability} and {@code chk_capability_grant_history_capability}; keep both
 * in sync.
 */
public enum Capability {
  /**
   * Creating a space. The personal space is provisioned at first sign-in, independently of this.
   */
  CREATE_SPACE,

  /** Creating a knowledge library whose documents are uploaded. */
  CREATE_LIBRARY,

  /**
   * Creating a knowledge library fed by a connector. Its own capability because such a library
   * reaches server paths and stored credentials. The only capability with a scope - one connector
   * type ({@code TYPE:<key>}) or one connection profile ({@code PROFILE:<id>}) - so releasing one
   * target never opens another (ADR-0036, Nachtrag of 03.10.2026).
   */
  CREATE_CONNECTOR_LIBRARY,

  /**
   * Creating an internal group. Delivered to nobody; a system administrator holds it implicitly.
   */
  CREATE_INTERNAL_GROUP,

  /**
   * Creating a prompt library. Delivered to all accounts: a prompt binds no knowledge and reaches
   * no source, so creating one widens nothing.
   */
  CREATE_PROMPT_LIBRARY
}
