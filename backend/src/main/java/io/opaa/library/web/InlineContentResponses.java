package io.opaa.library.web;

import io.opaa.knowledge.DocumentContent;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The response that serves a stored original inline with its own content type. The escaped file
 * name, {@code nosniff} and {@code Content-Security-Policy: default-src 'none'; sandbox} are the
 * only defence against an arbitrary user-supplied file becoming an execution vector.
 */
public final class InlineContentResponses {

  private static final Logger log = LoggerFactory.getLogger(InlineContentResponses.class);

  private InlineContentResponses() {}

  /** {@code content} as the body of a {@code 200}. */
  public static ResponseEntity<Resource> of(DocumentContent content) {
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
   * A malformed stored or remote content type becomes {@code application/octet-stream}, not 500.
   */
  private static MediaType parseMediaTypeOrOctetStream(String contentType) {
    try {
      return MediaType.parseMediaType(contentType);
    } catch (InvalidMediaTypeException e) {
      log.warn("Document content carried an unparsable Content-Type: {}", contentType, e);
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  /**
   * An RFC 6266 {@code Content-Disposition} carrying {@code fileName} twice: as an ASCII {@code
   * filename} fallback and exactly as UTF-8 {@code filename*}.
   */
  private static String contentDisposition(String fileName) {
    return "inline; filename=\""
        + asciiFallback(fileName)
        + "\"; filename*=UTF-8''"
        + rfc5987(fileName);
  }

  /**
   * {@code fileName} for the plain {@code filename} fallback: non-printable ASCII, {@code "} and
   * {@code \} become {@code _}, so the quoted string cannot be broken out of.
   */
  private static String asciiFallback(String fileName) {
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
   * Percent-encodes {@code fileName}'s UTF-8 bytes per RFC 8187's {@code attr-char}, including
   * {@code *}, {@code '} and the space, which {@link java.net.URLEncoder} gets wrong.
   */
  private static String rfc5987(String fileName) {
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
