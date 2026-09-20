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
   * Creating a knowledge library fed by a connector (filesystem, web directory, RSS, Confluence,
   * S3). Its own capability because such a library reaches server paths and stored credentials and
   * carries the sharing ceiling of #797 - the first capability an installation is expected to
   * narrow to a named group after the migration.
   */
  CREATE_CONNECTOR_LIBRARY,

  /**
   * Creating an internal group. Delivered to nobody; a system administrator holds it implicitly.
   */
  CREATE_INTERNAL_GROUP
}
