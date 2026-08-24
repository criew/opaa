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
 *     truncating (logged, not failed) - the root directory itself is depth 0, so a crawl visits
 *     depths {@code 0..maxDepth} inclusive ({@code maxDepth + 1} levels total). Default 10: deep
 *     enough for a realistic directory tree while bounding a same-origin navigation cycle (e.g. a
 *     symlink loop producing ever-longer URLs the visited-URL guard alone would not catch) to a
 *     small, fixed number of requests. {@code 0} falls back to the default; a negative value is
 *     rejected outright (mirrors {@link IndexingProperties}'s own validation style) rather than
 *     silently normalized, since a negative depth has no sensible interpretation at all.
 * @param maxEntries the maximum number of file entries a single crawl collects before truncating
 *     (logged, not failed) - mirrors {@code IndexingProperties.Rss#maxEntries}'s truncation-not-
 *     failure treatment. Also bounds the total number of directories a crawl visits (#836 review):
 *     a directory-only symlink cycle (every directory linking only to further directories, never a
 *     file) would otherwise never grow {@code results} at all and be bounded by {@link #maxDepth}
 *     alone, which - for a cycle with branching factor {@code b} - still means up to {@code
 *     b^maxDepth} requests. Default 5000: generous for an ordinary source while still bounding how
 *     much a single run materializes in memory before processing, and how many requests a
 *     directory-only cycle can force. {@code 0} falls back to the default; a negative value is
 *     rejected outright, mirroring {@code maxDepth} above.
 */
@ConfigurationProperties(prefix = "opaa.indexing.crawl")
public record CrawlProperties(int maxDepth, int maxEntries) {

  public CrawlProperties {
    if (maxDepth < 0) {
      throw new IllegalArgumentException("maxDepth must not be negative, got " + maxDepth);
    }
    if (maxDepth == 0) {
      maxDepth = 10;
    }
    if (maxEntries < 0) {
      throw new IllegalArgumentException("maxEntries must not be negative, got " + maxEntries);
    }
    if (maxEntries == 0) {
      maxEntries = 5000;
    }
  }
}
