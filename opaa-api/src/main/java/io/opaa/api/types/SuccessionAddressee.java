package io.opaa.api.types;

/**
 * Who is meant to act on an entry of the operational list (ADR-0036, Entscheidung 6). <b>A
 * statement of responsibility, never a restriction of access to the list</b>: the list holds every
 * open succession from day one, whoever the addressee is - without that, a case of the second stage
 * would never reach the third.
 */
public enum SuccessionAddressee {

  /** A space: its remaining capable {@code ADMIN} members. */
  SPACE_ADMINS,

  /** An asset owned by an internal group: that group's stewards. */
  GROUP_STEWARDS,

  /** Everything else - an asset of a person, an asset of a directory group, a group without one. */
  SYSTEM_ADMINISTRATION
}
