package io.opaa.indexing.filesync;

/**
 * What proves a document's file gone once a full sync spanned several runs. A sync that listed
 * every container within one run proves it for every store; the proof only decides a round over
 * several runs, where a file may move from a part not yet listed into one already listed.
 */
public enum AbsenceProof {

  /** Only a single run proves absence: a round over several runs removes nothing. */
  SINGLE_RUN,

  /**
   * The identity is the location: a file met nowhere in the round is gone under its identity, so
   * the round removes what its runs did not see.
   */
  LOCATION_IDENTITY
}
