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

  /**
   * One page of the changes of {@code feedKey} from {@code cursor} on - a stored cursor or the
   * {@link ChangePage#next()} of the page before.
   *
   * @throws FileAccessException.CursorExpired when the source no longer accepts {@code cursor}
   */
  ChangePage read(String feedKey, String cursor) throws FileAccessException, InterruptedException;

  /**
   * Whether {@code container} can be reached now. A removal reported by a stream counts only while
   * every container it serves is reachable - a withdrawn right is no deletion finding.
   *
   * @throws FileAccessException.ContainerUnlistable when it cannot
   */
  void requireReachable(FileContainer container) throws FileAccessException, InterruptedException;
}
