package io.opaa.indexing.source.confluence;

import io.opaa.sourceaccess.BoundedDownloader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * What both adapters share: the HTTP helper, JSON field access that never throws on a missing
 * field, CQL construction, the bounded pagination loop that follows the instance's own {@code
 * _links.next} (resolved against the connection's origin, never anywhere else) and the attachment
 * download. Edition-specific paths and response shapes stay in the subclasses.
 */
abstract class AbstractConfluenceClient implements ConfluenceClient {

  protected final ConfluenceHttp http;

  AbstractConfluenceClient(ConfluenceHttp http) {
    this.http = http;
  }

  @Override
  public ConfluenceRequestMeter meter() {
    return http.meter();
  }

  @Override
  public BoundedDownloader.DownloadedFile downloadAttachment(ConfluenceAttachment attachment)
      throws ConfluenceAccessException, InterruptedException {
    return downloadAttachment(attachment, http.properties().maxAttachmentSizeBytes());
  }

  @Override
  public BoundedDownloader.DownloadedFile downloadAttachment(
      ConfluenceAttachment attachment, long maxBytes)
      throws ConfluenceAccessException, InterruptedException {
    return http.download(attachment.downloadUrl(), attachment.fileName(), maxBytes);
  }

  protected int pageSize() {
    return http.properties().pageSize();
  }

  protected String base() {
    return http.connection().baseUrl().toString();
  }

  /** One page of a listing plus how to reach the next one ({@code null} at the end). */
  protected record Listing(List<JsonNode> results, String nextUrl) {}

  /** How a subclass turns a raw response page into results and the resolved {@code next} URL. */
  @FunctionalInterface
  protected interface PageReader {
    Listing read(JsonNode page) throws ConfluenceAccessException;
  }

  /**
   * Follows a listing from {@code firstUrl} to its end, bounded by {@link
   * ConfluenceHttp.ListingGuard} against a {@code next} that never runs out or repeats itself.
   */
  protected List<JsonNode> listAll(String firstUrl, String resource, PageReader reader)
      throws ConfluenceAccessException, InterruptedException {
    List<JsonNode> all = new ArrayList<>();
    ConfluenceHttp.ListingGuard guard = http.new ListingGuard(resource);
    String url = firstUrl;
    while (url != null) {
      guard.visit(url);
      Listing listing = reader.read(http.getJson(url, resource));
      all.addAll(listing.results());
      url = listing.results().isEmpty() ? null : listing.nextUrl();
    }
    return all;
  }

  /**
   * {@code type=page AND space in (...) AND lastmodified >= now("-Nm")} - the window as minutes
   * before the instance's own {@code now}, so the instance evaluates it in its clock and time zone
   * (an absolute timestamp would be read in the instance's zone, not UTC). {@code N} is rounded up,
   * so the window never starts after {@code since}; callers add their own overlap. No {@code
   * expand} of bodies is ever appended to a search (guarded by the contract test).
   */
  protected static String changedPagesCql(Set<String> spaceKeys, Instant since) {
    if (spaceKeys == null || spaceKeys.isEmpty()) {
      throw new IllegalArgumentException("spaceKeys must not be empty");
    }
    StringBuilder cql = new StringBuilder("type=page AND space in (");
    boolean first = true;
    for (String key : new TreeSet<>(spaceKeys)) {
      if (!first) {
        cql.append(',');
      }
      first = false;
      cql.append('"').append(key.replace("\"", "")).append('"');
    }
    cql.append(") AND lastmodified >= ")
        .append(relativeWindow(since))
        .append(" ORDER BY lastmodified ASC");
    return cql.toString();
  }

  /** {@code now("-Nm")} for a past {@code since}, {@code now("+Nm")} for a future one. */
  static String relativeWindow(Instant since) {
    long seconds = java.time.Duration.between(since, Instant.now()).getSeconds();
    if (seconds >= 0) {
      long minutes = seconds / 60 + 1;
      return "now(\"-" + minutes + "m\")";
    }
    long minutes = Math.max(0, (-seconds) / 60);
    return "now(\"+" + minutes + "m\")";
  }

  /** Query-parameter encoding. */
  protected static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** Path-segment encoding: like {@link #encode} but with spaces as {@code %20}, not {@code +}. */
  protected static String segment(String value) {
    return encode(value).replace("+", "%20");
  }

  protected static String text(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return node.stringValue();
    }
    if (node.isNumber()) {
      return node.numberValue().toString();
    }
    return null;
  }

  protected static String text(JsonNode node, String field) {
    return node == null ? null : text(node.path(field));
  }

  protected static int intOr(JsonNode node, int fallback) {
    if (node == null || !node.isNumber()) {
      return fallback;
    }
    return node.intValue();
  }

  protected static long longOr(JsonNode node, long fallback) {
    if (node == null || !node.isNumber()) {
      return fallback;
    }
    return node.longValue();
  }

  protected static List<JsonNode> results(JsonNode page) {
    List<JsonNode> list = new ArrayList<>();
    JsonNode results = page.path("results");
    if (results.isArray()) {
      results.forEach(list::add);
    }
    return list;
  }

  /** {@code _links.next} of a page, or {@code null} when the listing ends. */
  protected static String nextLink(JsonNode page) {
    String next = text(page.path("_links"), "next");
    return next == null || next.isBlank() ? null : next;
  }

  protected static Instant instantOrNull(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso);
    } catch (RuntimeException e) {
      try {
        return java.time.OffsetDateTime.parse(iso).toInstant();
      } catch (RuntimeException ignored) {
        return null;
      }
    }
  }
}
