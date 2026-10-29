package io.opaa.library;

import io.opaa.connection.profile.ServerAddress;

/**
 * Whether a change of a stored library's own address opens a new target - which, like a new
 * library, needs the connector release of its type. The origin of an address is scheme, host and
 * port ({@link ServerAddress#sameOrigin}); a library without an address (a directory) is identified
 * by its path. An address or path left out is no change.
 */
final class SourceOrigin {

  private SourceOrigin() {}

  static boolean changes(String beforeUrl, String beforePath, String afterUrl, String afterPath) {
    if (afterUrl != null) {
      return !afterUrl.equals(beforeUrl) && !ServerAddress.sameOrigin(beforeUrl, afterUrl);
    }
    return beforeUrl == null && afterPath != null && !afterPath.equals(beforePath);
  }
}
