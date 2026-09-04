package io.opaa.indexing.source.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds an {@code HTTP_DIRECTORY} run: {@link AutoindexCrawlerService}'s recursive descent and the
 * size of a single entry {@link UrlIndexingExecutor} transfers - deliberately its own property
 * block rather than a component of {@code IndexingProperties} (mirrors {@code UploadProperties}'s
 * own reasoning): {@link AutoindexCrawlerService} is a plain, non-Spring-bean class with a single
 * test-friendly constructor, and adding these values there would touch every one of {@link
 * IndexingProperties}'s many positional-record call sites for a concern specific to the {@code
 * HTTP_DIRECTORY} crawl path alone.
 *
 * @param maxDepth the maximum recursion depth {@link AutoindexCrawlerService#crawl} descends before
 *     truncating (logged, not failed) - the root directory itself is depth 0, so a crawl visits
 *     depths {@code 0..maxDepth} inclusive. Default 10: deep enough for a realistic directory tree
 *     while bounding a same-origin navigation cycle to a small, fixed number of requests. {@code 0}
 *     falls back to the default; a negative value is rejected outright.
 * @param maxEntries the maximum number of file entries a single crawl collects before truncating
 *     (logged, not failed) - mirrors {@code IndexingProperties.Rss#maxEntries}'s truncation-not-
 *     failure treatment. Also bounds the total number of directories a crawl visits: a
 *     directory-only symlink cycle would otherwise never grow {@code results} at all and be bounded
 *     by {@link #maxDepth} alone, up to {@code b^maxDepth} requests for a cycle with branching
 *     factor {@code b}. Default 5000. {@code 0} falls back to the default; a negative value is
 *     rejected outright.
 * @param maxFileSizeBytes the maximum number of bytes a single crawled entry may transfer before
 *     {@code BoundedDownloader#download} aborts it - enforced while streaming to disk, so neither
 *     the temp partition nor the heap ever holds more than this much of one entry, whether it is
 *     indexed or rejected afterwards (#1236). Default 104 857 600 (100 MiB): the same order as
 *     {@code MailProperties#maxMessageBytes}, and deliberately below the 128 MiB {@code markLimit}
 *     of Tika's POIFS container detection, past which an OLE2 entry would be rejected after a full
 *     transfer anyway. {@code 0} falls back to the default; a negative value is rejected outright.
 */
@ConfigurationProperties(prefix = "opaa.indexing.crawl")
public record CrawlProperties(int maxDepth, int maxEntries, long maxFileSizeBytes) {

  /** Default entry size cap - see {@link #maxFileSizeBytes}. */
  public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 104_857_600L;

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
    if (maxFileSizeBytes < 0) {
      throw new IllegalArgumentException(
          "maxFileSizeBytes must not be negative, got " + maxFileSizeBytes);
    }
    if (maxFileSizeBytes == 0) {
      maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;
    }
  }
}
