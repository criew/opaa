package io.opaa.indexing.source.rss;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RssFeedStateRepository extends JpaRepository<RssFeedState, UUID> {

  // Keyed per library, not per feed URL alone - see RssFeedState's Javadoc for why.
  Optional<RssFeedState> findByLibraryIdAndFeedUrl(UUID libraryId, String feedUrl);

  // KnowledgeLibraryService#updateLibrary calls this when a library's sourceUrl changes, so a
  // later reconfiguration back to a previously-used address never resurrects that address's own
  // stale ETag/Last-Modified state - the ON DELETE CASCADE only fires on a library deletion, not
  // a sourceUrl change on an otherwise-surviving library.
  long deleteByLibraryId(UUID libraryId);
}
