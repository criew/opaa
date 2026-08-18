package io.opaa.indexing;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.nodes.Element;

/**
 * Describes which links on an RSS entry's detail page count as attachments (#468) - a named,
 * configurable answer to "which of this page's links are documents", kept as an explicit code
 * construct instead of an {@code if} chain inside {@link RssFeedIndexingExecutor}: a second CMS
 * with a different attachment pattern becomes a third enum constant, not a new branch in the
 * executor. Selected per deployment via {@code opaa.indexing.rss.attachment-profile} ({@link
 * IndexingProperties.Rss#attachmentProfile()}), not per run and not by guessing the CMS from the
 * feed's address (#468 explicitly rules out address-based CMS detection).
 *
 * <p>Both profiles search only inside the same content area {@link RssFeedIndexingExecutor} already
 * extracts the article text from ({@code opaa.indexing.rss.main-content-selector}) - a profile's
 * "Einschränkung auf einen Bereich der Seite" from the issue's profile concept is therefore not a
 * second, separately configured selector but the boundary already drawn for the article text
 * itself. A link outside that area (site navigation, a footer's imprint link) is never an
 * attachment under either profile, and neither profile ever considers a link to a different host
 * than the detail page's own - the #468 acceptance criterion "Verweise, die aus der Seite
 * hinausführen, gelten nicht als Anlage" applies across both.
 */
public enum AttachmentProfile {

  /**
   * The default profile (#468 acceptance criteria: "ohne Profilangabe greift das allgemeine
   * Profil"): a link is an attachment when it points at one of the {@link SupportedDocumentFormats}
   * extensions and stays on the detail page's own host.
   */
  GENERIC {
    @Override
    List<AttachmentCandidate> findAttachments(Element contentArea, URI pageUri) {
      // LinkedHashSet, not List (PR #492 review, finding 8): the same attachment is routinely
      // linked twice on one detail page (an inline text link plus a "downloads" list at the
      // bottom) - without dedup here, the executor would download it twice, wait out politeness
      // twice, and exhaust maxAttachmentsPerEntry on duplicates rather than distinct attachments.
      Set<AttachmentCandidate> candidates = new LinkedHashSet<>();
      for (Element link : contentArea.select("a[href]")) {
        String absoluteUrl = link.absUrl("href");
        URI linkUri = parseHttpOrHttps(absoluteUrl);
        if (linkUri == null || !sameHost(pageUri, linkUri)) {
          continue;
        }
        String fileName = lastPathSegment(linkUri);
        if (!SupportedDocumentFormats.isSupported(fileName)) {
          continue;
        }
        candidates.add(new AttachmentCandidate(absoluteUrl, fileName));
      }
      return List.copyOf(candidates);
    }
  },

  /**
   * The Government Site Builder profile (#468): the CMS of the German federal government does not
   * expose attachments under a URL with a file extension - it serves them through a query parameter
   * on the page's own address instead ({@code __blob=publicationFile}). The pattern below is the
   * generic shape of that mechanism; this class's own tests reproduce it exclusively with fictional
   * {@code example.gov}-style addresses, never a real institution's domain (#468 acceptance
   * criteria).
   */
  GSB {
    @Override
    List<AttachmentCandidate> findAttachments(Element contentArea, URI pageUri) {
      // See GENERIC's own comment on the same dedup (PR #492 review, finding 8).
      Set<AttachmentCandidate> candidates = new LinkedHashSet<>();
      for (Element link : contentArea.select("a[href]")) {
        String absoluteUrl = link.absUrl("href");
        URI linkUri = parseHttpOrHttps(absoluteUrl);
        if (linkUri == null || !sameHost(pageUri, linkUri) || !isBlobLink(linkUri)) {
          continue;
        }
        candidates.add(new AttachmentCandidate(absoluteUrl, lastPathSegment(linkUri)));
      }
      return List.copyOf(candidates);
    }

    private boolean isBlobLink(URI linkUri) {
      String query = linkUri.getRawQuery();
      if (query == null) {
        return false;
      }
      for (String param : query.split("&")) {
        if (param.equals("__blob=publicationFile")) {
          return true;
        }
      }
      return false;
    }
  };

  /**
   * Finds every attachment {@code contentArea} - the same element {@link RssFeedIndexingExecutor}
   * already extracted the article text from - links to, according to this profile's rules. Never
   * throws on a malformed link; a link this profile cannot make sense of is simply not an
   * attachment.
   */
  abstract List<AttachmentCandidate> findAttachments(Element contentArea, URI pageUri);

  private static URI parseHttpOrHttps(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      if (scheme == null) {
        return null;
      }
      String lowerScheme = scheme.toLowerCase(Locale.ROOT);
      if (!lowerScheme.equals("http") && !lowerScheme.equals("https")) {
        return null;
      }
      return uri;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /**
   * Whether {@code linkUri} is the same origin as {@code pageUri} - host, scheme and port all have
   * to match (PR #492 review, finding 9). Host alone let an {@code http} link on an {@code https}
   * detail page through, silently downgrading the attachment download to plain text; port alone let
   * a link to an unrelated service on the same host through. Default ports ({@code 80}/{@code 443})
   * are normalised so an explicit {@code :443} on an {@code https} link still counts as the same
   * origin as one without.
   */
  private static boolean sameHost(URI pageUri, URI linkUri) {
    return pageUri.getHost() != null
        && linkUri.getHost() != null
        && pageUri.getHost().equalsIgnoreCase(linkUri.getHost())
        && pageUri.getScheme() != null
        && linkUri.getScheme() != null
        && pageUri.getScheme().equalsIgnoreCase(linkUri.getScheme())
        && normalizedPort(pageUri) == normalizedPort(linkUri);
  }

  private static int normalizedPort(URI uri) {
    int port = uri.getPort();
    if (port != -1) {
      return port;
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      return -1;
    }
    return switch (scheme.toLowerCase(Locale.ROOT)) {
      case "https" -> 443;
      case "http" -> 80;
      default -> -1;
    };
  }

  private static String lastPathSegment(URI uri) {
    String path = uri.getPath();
    if (path == null || path.isBlank()) {
      return "";
    }
    int lastSlash = path.lastIndexOf('/');
    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
  }
}
