package io.opaa.indexing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RssFeedStateRepository extends JpaRepository<RssFeedState, UUID> {

  Optional<RssFeedState> findByFeedUrl(String feedUrl);
}
