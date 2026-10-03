package io.opaa.indexing.source.nextcloud;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Percent-encoding of WebDAV paths: a {@code +} is a plus, never a space. */
final class DavPaths {

  private DavPaths() {}

  /** The path of {@code href}, which is either an absolute path or an absolute URL. */
  static String pathOf(String href) {
    int scheme = href.indexOf("://");
    if (scheme < 0) {
      return href;
    }
    int slash = href.indexOf('/', scheme + 3);
    return slash < 0 ? "/" : href.substring(slash);
  }

  /** Decodes every {@code %XX} as UTF-8 bytes; anything else stays as it is. */
  static String decode(String encoded) {
    if (encoded.indexOf('%') < 0) {
      return encoded;
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
    int i = 0;
    while (i < encoded.length()) {
      int codePoint = encoded.codePointAt(i);
      if (codePoint == '%' && isHex(encoded, i + 1)) {
        bytes.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
        i += 3;
      } else {
        bytes.writeBytes(Character.toString(codePoint).getBytes(StandardCharsets.UTF_8));
        i += Character.charCount(codePoint);
      }
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static boolean isHex(String text, int start) {
    return start + 1 < text.length()
        && Character.digit(text.charAt(start), 16) >= 0
        && Character.digit(text.charAt(start + 1), 16) >= 0;
  }

  /** Encodes one path segment; {@code /} inside it is encoded too. */
  static String encodeSegment(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Encodes every segment of a slash-separated path, keeping its slashes. */
  static String encodePath(String path) {
    List<String> encoded = new ArrayList<>();
    for (String segment : path.split("/", -1)) {
      encoded.add(encodeSegment(segment));
    }
    return String.join("/", encoded);
  }
}
