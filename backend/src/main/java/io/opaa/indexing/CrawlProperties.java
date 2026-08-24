package io.opaa.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds {@link AutoindexCrawlerService}'s recursive descent (#836) - deliberately its own property
 * block rather than a component of {@link IndexingProperties} (mirrors {@code UploadProperties}'s
 * own reasoning): {@link AutoindexCrawlerService} is a plain, non-Spring-bean class with a single
 * test-friendly constructor, and adding these two values there would touch every one of {@link
 * IndexingProperties}'s many positional-record call sites for a concern specific to the {@code
 * HTTP_DIRECTORY} crawl path alone.
 *
 * @param maxDepth the maximum recursion depth {@link AutoindexCrawlerService#crawl} descends before
 *     truncating (logged, not failed). Default 10: deep enough for a realistic directory tree while
 *     bounding a same-origin navigation cycle (e.g. a symlink loop producing ever-longer URLs the
 *     visited-URL guard alone would not catch) to a small, fixed number of requests.
 * @param maxEntries the maximum number of file entries a single crawl collects before truncating
 *     (logged, not failed) - mirrors {@code IndexingProperties.Rss#maxEntries}'s truncation-not-
 *     failure treatment. Default 5000: generous for an ordinary source while still bounding how
 *     much a single run materializes in memory before processing.
 */
@ConfigurationProperties(prefix = "opaa.indexing.crawl")
public record CrawlProperties(int maxDepth, int maxEntries) {

  public CrawlProperties {
    if (maxDepth <= 0) {
      maxDepth = 10;
    }
    if (maxEntries <= 0) {
      maxEntries = 5000;
    }
  }
}
