package io.opaa.indexing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RssFeedStateRepository extends JpaRepository<RssFeedState, UUID> {

  // #646: keyed per library, not per feed URL alone - see RssFeedState's Javadoc for why.
  Optional<RssFeedState> findByLibraryIdAndFeedUrl(UUID libraryId, String feedUrl);
}
