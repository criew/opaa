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
  LOCATION_IDENTITY,

  /**
   * The store's change log ({@link FileStore#changes()}) from the cursors held when the round
   * began: at the round's end every stream is read to its last page, and only after a clean read
   * does the round remove what neither its runs nor the log saw. A store declaring it has a change
   * log.
   */
  CHANGE_FEED
}
