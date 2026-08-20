package io.opaa.indexing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RssFeedStateRepository extends JpaRepository<RssFeedState, UUID> {

  // #646: keyed per library, not per feed URL alone - see RssFeedState's Javadoc for why.
  Optional<RssFeedState> findByLibraryIdAndFeedUrl(UUID libraryId, String feedUrl);

  // #646, PR #665 review "should" finding 3: KnowledgeLibraryService#updateLibrary calls this when
  // a library's sourceUrl changes, so a later reconfiguration back to a previously-used address
  // never resurrects that address's own stale ETag/Last-Modified state for the same library -
  // fk_rss_feed_state_library's ON DELETE CASCADE only ever fires on a library *deletion*, not a
  // sourceUrl change on an otherwise-surviving library.
  long deleteByLibraryId(UUID libraryId);
}
