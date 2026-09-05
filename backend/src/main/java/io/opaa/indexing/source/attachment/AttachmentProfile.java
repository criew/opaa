package io.opaa.indexing.source.attachment;

import io.opaa.indexing.SupportedDocumentFormats;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.nodes.Element;

/**
 * Describes which links on an RSS entry's detail page count as attachments - an explicit code
 * construct rather than an {@code if} chain, so a second CMS with a different pattern becomes a
 * third constant. Selected per deployment via {@code opaa.indexing.rss.attachment-profile}, never
 * by guessing the CMS from the feed's address.
 *
 * <p>Both profiles search only inside the content area the article text is extracted from, so a
 * navigation or footer link is never an attachment, and neither ever considers a link to a
 * different host than the detail page's own.
 */
public enum AttachmentProfile {

  /**
   * The default profile: a link is a candidate when it carries some file extension at all and stays
   * on the detail page's own host - {@code fileHasSomeExtension}, not {@link
   * SupportedDocumentFormats#isSupported}, because this method only ever sees a URL. Filtering to
   * the recognized extensions here would silently exclude a document linked under the wrong one (a
   * PDF published as {@code bescheid.csv}); acceptance is decided later, from the bytes.
   */
  GENERIC {
    @Override
    public List<AttachmentCandidate> findAttachments(Element contentArea, URI pageUri) {
      // LinkedHashSet, not List: the same attachment is routinely linked twice on one detail page
      // (an inline text link plus a "downloads" list at the bottom) - without dedup here, the
      // executor would download it twice and exhaust maxAttachmentsPerEntry on duplicates.
      Set<AttachmentCandidate> candidates = new LinkedHashSet<>();
      for (Element link : contentArea.select("a[href]")) {
        String absoluteUrl = link.absUrl("href");
        URI linkUri = parseHttpOrHttps(absoluteUrl);
        if (linkUri == null || !sameHost(pageUri, linkUri)) {
          continue;
        }
        String fileName = lastPathSegment(linkUri);
        if (!fileHasSomeExtension(fileName)) {
          continue;
        }
        candidates.add(new AttachmentCandidate(absoluteUrl, fileName));
      }
      return List.copyOf(candidates);
    }
  },

  /**
   * The Government Site Builder profile: the CMS of the German federal government does not expose
   * attachments under a URL with a file extension - it serves them through a query parameter on the
   * page's own address instead ({@code __blob=publicationFile}). This class's own tests reproduce
   * it exclusively with fictional {@code example.gov}-style addresses, never a real institution's
   * domain.
   */
  GSB {
    @Override
    public List<AttachmentCandidate> findAttachments(Element contentArea, URI pageUri) {
      // See GENERIC's own comment on the same dedup.
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
   * Finds every attachment {@code contentArea} - the same element {@code RssFeedIndexingExecutor}
   * already extracted the article text from - links to, according to this profile's rules. Never
   * throws on a malformed link; a link this profile cannot make sense of is simply not an
   * attachment.
   */
  public abstract List<AttachmentCandidate> findAttachments(Element contentArea, URI pageUri);

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
   * to match. Host alone would let an {@code http} link on an {@code https} detail page through,
   * silently downgrading the attachment download to plain text; port alone would let a link to an
   * unrelated service on the same host through. Default ports are normalised so an explicit {@code
   * :443} on an {@code https} link still counts as the same origin as one without.
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

  /**
   * Whether {@code fileName}'s last path segment carries a file extension at all - a dot that is
   * neither the first nor the last character. The structural "this looks like a file" signal {@link
   * #GENERIC} uses instead of {@link SupportedDocumentFormats#isSupported}. Package-visible so
   * {@code AttachmentIndexer#resolveFileName} applies the identical rule.
   */
  static boolean fileHasSomeExtension(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return false;
    }
    int lastDot = fileName.lastIndexOf('.');
    return lastDot > 0 && lastDot < fileName.length() - 1;
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
