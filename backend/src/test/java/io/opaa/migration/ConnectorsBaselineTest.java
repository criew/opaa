package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the baseline's connectors changeSet: the RSS connector's per-library feed state.
 */
class ConnectorsBaselineTest extends AbstractBaselineTest {

  private static final String FEED_URL = "https://example.com/feed.xml";

  /**
   * The exact fix #646 required: the state is keyed by library and feed address, so two libraries
   * reading the same feed keep their own state - and each loses only its own when it is deleted.
   */
  @Test
  void twoLibrariesKeepTheirOwnStateForTheSameFeedAndDeletingOneKeepsTheOther()
      throws SQLException {
    UUID first = insertLibrary("RSS_FEED", FEED_URL);
    UUID second = insertLibrary("RSS_FEED", FEED_URL);
    execute(feedStateSql(first, "\"etag-a\""));
    execute(feedStateSql(second, "\"etag-b\""));

    assertRejected(feedStateSql(first, "\"etag-a-again\""), "uk_rss_feed_state_library_feed_url");
    execute("DELETE FROM assets WHERE id = '" + first + "'");
    assertThat(countWhere("rss_feed_state", "library_id = '" + first + "'")).isZero();
    assertThat(countWhere("rss_feed_state", "library_id = '" + second + "'")).isEqualTo(1);
  }

  private static String feedStateSql(UUID library, String etag) {
    return "INSERT INTO rss_feed_state (id, library_id, feed_url, etag, updated_at) VALUES"
        + " (gen_random_uuid(), '"
        + library
        + "', '"
        + FEED_URL
        + "', '"
        + etag
        + "', now())";
  }
}
