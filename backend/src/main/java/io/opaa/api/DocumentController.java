package io.opaa.api;

import io.opaa.auth.CurrentUser;
import io.opaa.library.DocumentContent;
import io.opaa.library.LibraryDocumentService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the original file behind an indexed document (#736), so a search result or a library
 * listing can link straight to the source it was built from - the read counterpart to {@code
 * LibraryController}'s upload/delete document endpoints, split into its own controller because the
 * library owning the document is resolved from {@code Document.libraryId} rather than named in the
 * path (see {@link LibraryDocumentService#loadContent}'s Javadoc for the full access, sourceType
 * and traversal checks this delegates to).
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

  private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

  private final LibraryDocumentService documentService;

  public DocumentController(LibraryDocumentService documentService) {
    this.documentService = documentService;
  }

  /**
   * {@code Content-Disposition: inline} with the document's own file name, carried both as a plain
   * ASCII {@code filename} fallback and as an RFC 5987 {@code filename*} - the same reasoning
   * {@link BrandingController#getBrandingLogo} documents for its own, fixed file name applies here
   * to a caller-influenced one: an unescaped file name containing a quote or CR/LF could otherwise
   * break out of the header value. Unlike the logo endpoint, the content type varies per document,
   * so it is taken from {@link DocumentContent#contentType()} rather than fixed to image formats.
   *
   * <p>Three headers keep a stored, user-supplied file from becoming an execution vector when
   * opened inline - mirrors {@link BrandingController#getBrandingLogo}'s own three, except this
   * endpoint serves arbitrary indexed files rather than a format {@code BrandingLogoValidator}
   * already forces to a real image, so these headers are the only line of defense here, not a
   * second one (#742 review, finding 1):
   *
   * <ul>
   *   <li>{@code X-Content-Type-Options: nosniff}
   *   <li>{@code Content-Disposition: inline} with the escaped, caller-influenced file name above
   *   <li>{@code Content-Security-Policy: default-src 'none'; sandbox}
   * </ul>
   */
  @GetMapping("/{documentId}/content")
  public ResponseEntity<Resource> getDocumentContent(
      @PathVariable UUID documentId, CurrentUser caller) {
    DocumentContent content = documentService.loadContent(documentId, caller);

    Resource resource =
        content.isStreamed()
            ? new InputStreamResource(content.stream())
            : new FileSystemResource(content.path());
    return ResponseEntity.ok()
        .contentType(parseMediaTypeOrOctetStream(content.contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(content.fileName()))
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        .body(resource);
  }

  /**
   * {@link MediaType#parseMediaType} falls back to {@code application/octet-stream} instead of
   * propagating {@link InvalidMediaTypeException} (#748 review, finding 2a) - that exception is an
   * {@link IllegalArgumentException}, uncaught here would surface as a 500 for a stored or
   * remote-declared {@code Content-Type} this class does not control the syntax of (a malformed
   * value from a remote source proxied by {@link LibraryDocumentService#loadContent}), turning
   * "source sent something odd" into an OPAA-side server error instead of a merely generic
   * download.
   */
  private MediaType parseMediaTypeOrOctetStream(String contentType) {
    try {
      return MediaType.parseMediaType(contentType);
    } catch (InvalidMediaTypeException e) {
      log.warn("Document content carried an unparsable Content-Type: {}", contentType, e);
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  /**
   * Builds an RFC 6266 {@code Content-Disposition} value carrying {@code fileName} twice: an ASCII
   * {@code filename} a client without RFC 5987 support falls back to, and the exact, UTF-8 {@code
   * filename*} every modern browser actually uses (#742 review, nit 4). {@link java.net.URLEncoder}
   * is deliberately not used for the latter - it targets {@code application/x-www-form-urlencoded},
   * which leaves {@code *} unescaped even though RFC 8187's {@code attr-char} does not include it,
   * and encodes a space as {@code +} rather than {@code %20}.
   */
  private String contentDisposition(String fileName) {
    return "inline; filename=\""
        + asciiFallback(fileName)
        + "\"; filename*=UTF-8''"
        + rfc5987(fileName);
  }

  /**
   * A best-effort ASCII rendering of {@code fileName} for the plain {@code filename} fallback:
   * anything outside the printable ASCII range, and the two characters ({@code "} and {@code \})
   * that would otherwise break out of the quoted-string, become {@code _}. The exact name is only
   * ever carried faithfully by {@code filename*} - this fallback merely has to be a harmless
   * display name for a client that does not understand RFC 5987 at all.
   */
  private String asciiFallback(String fileName) {
    StringBuilder result = new StringBuilder(fileName.length());
    for (int i = 0; i < fileName.length(); i++) {
      char c = fileName.charAt(i);
      result.append(c >= 0x20 && c < 0x7F && c != '"' && c != '\\' ? c : '_');
    }
    return result.toString();
  }

  /** RFC 8187 {@code attr-char} - everything else in an ext-value must be percent-encoded. */
  private static final String RFC8187_ATTR_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$&+-.^_`|~";

  /**
   * Percent-encodes {@code fileName}'s UTF-8 bytes per RFC 8187's {@code attr-char} (#742 review,
   * nit 4) - notably including {@code *} and {@code '}, which {@link java.net.URLEncoder} would
   * leave unescaped even though neither is a valid {@code attr-char}.
   */
  private String rfc5987(String fileName) {
    StringBuilder result = new StringBuilder();
    for (byte b : fileName.getBytes(StandardCharsets.UTF_8)) {
      int unsigned = b & 0xFF;
      if (unsigned < 0x80 && RFC8187_ATTR_CHARS.indexOf((char) unsigned) >= 0) {
        result.append((char) unsigned);
      } else {
        result.append('%').append(String.format("%02X", unsigned));
      }
    }
    return result.toString();
  }
}
