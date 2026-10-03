package io.opaa.indexing.filesync;

import java.util.Objects;

/**
 * One configured area of a file store - a bucket with its prefix, a shared drive, a folder. The
 * {@code key} names it in the resumption state, the listing assessment and the protocol, so it
 * carries no comma and no line break.
 */
public record FileContainer(String key) {

  public FileContainer {
    Objects.requireNonNull(key, "key");
    if (key.isBlank() || key.indexOf(',') >= 0 || key.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("not a container key: " + key);
    }
  }
}
