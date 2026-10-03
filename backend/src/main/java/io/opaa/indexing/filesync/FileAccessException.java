package io.opaa.indexing.filesync;

/**
 * A store's failure in the neutral kinds {@link FileSync} acts on; the store translates its own
 * errors into them. {@link #getMessage()} is a German, user-facing sentence that never carries a
 * credential, and no cause is attached.
 */
public abstract sealed class FileAccessException extends Exception {

  private FileAccessException(String message) {
    super(message);
  }

  /** The container cannot be listed (refused, missing, cut short): no deletion finding. */
  public static final class ContainerUnlistable extends FileAccessException {
    public ContainerUnlistable(String message) {
      super(message);
    }
  }

  /** The source confirms the file no longer exists - the positive finding a deletion needs. */
  public static final class Gone extends FileAccessException {
    public Gone(String message) {
      super(message);
    }
  }

  /** The credentials may not read the file; a stored version is kept. */
  public static final class Unreadable extends FileAccessException {
    public Unreadable(String message) {
      super(message);
    }
  }

  /** The file exists but cannot be fetched as it is (an archive class without a restore). */
  public static final class Unavailable extends FileAccessException {
    public Unavailable(String message) {
      super(message);
    }
  }

  /** The file exceeds the size bound handed to {@link FileStore#fetch}. */
  public static final class TooLarge extends FileAccessException {
    public TooLarge(String message) {
      super(message);
    }
  }

  /** No later request of this run will do better (credentials, clock, TLS, blocked target). */
  public static final class RunEnding extends FileAccessException {
    public RunEnding(String message) {
      super(message);
    }
  }

  /** This one request failed (a throttle that outlasted its retries, an odd answer). */
  public static final class Transient extends FileAccessException {
    public Transient(String message) {
      super(message);
    }
  }
}
