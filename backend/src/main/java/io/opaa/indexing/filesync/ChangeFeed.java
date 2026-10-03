package io.opaa.indexing.filesync;

/**
 * The change log of a store that has one (ADR-0040, Entscheidung 6). A store serves its containers
 * from one or more streams, each read from a cursor the store hands out.
 */
public interface ChangeFeed {

  /** The stream that reports the changes of {@code container}. */
  String feedKey(FileContainer container);

  /**
   * The cursor that reports every change from now on - fetched before a full sync lists anything,
   * so no change during the listing is lost.
   */
  String startCursor(String feedKey) throws FileAccessException, InterruptedException;
}
