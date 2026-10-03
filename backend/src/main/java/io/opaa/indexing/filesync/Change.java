package io.opaa.indexing.filesync;

import java.util.Objects;

/** One change a stream reports, resolved by the store. */
public sealed interface Change {

  /** A file that exists in one of the containers, in its current state. */
  record Updated(FileEntry entry) implements Change {
    public Updated {
      Objects.requireNonNull(entry, "entry");
    }
  }

  /**
   * A file the source reports deleted, or that lies in no container any more; {@code filePath} is
   * the document identity a removal takes away.
   */
  record Removed(String filePath) implements Change {
    public Removed {
      Objects.requireNonNull(filePath, "filePath");
    }
  }
}
