package io.opaa.sourceaccess;

import java.util.Objects;

/**
 * How operational logs name the items of one library - a path, a URL, a file, an attachment, a
 * sheet, a title: by the name itself, or, where the administration that reads the logs must not
 * learn them (a private library), by an opaque reference. The library decides once ({@code
 * KnowledgeLibrary#loggedNames}); every log line on its run, intake and erasure goes through this.
 */
public final class LoggedName {

  /** Names show as they are. */
  public static final LoggedName OPEN = new LoggedName(null);

  private final String reference;

  private LoggedName(String reference) {
    this.reference = reference;
  }

  /** Every name shows as {@code reference}, which must name nothing of the content. */
  public static LoggedName withheld(String reference) {
    return new LoggedName(Objects.requireNonNull(reference, "reference"));
  }

  public boolean isWithheld() {
    return reference != null;
  }

  /** {@code name} where names show, else the reference; {@code null} stays {@code null}. */
  public String of(Object name) {
    return name == null ? null : reference == null ? name.toString() : reference;
  }

  /** {@code name} where names show, else the reference with {@code id}, which names nothing. */
  public String of(Object name, Object id) {
    return name == null ? null : reference == null ? name.toString() : reference + "/" + id;
  }

  /**
   * {@code thrown} where names show; else a stand-in with its type and stack trace but without any
   * message, which may carry a name - its causes alike.
   */
  public Throwable of(Throwable thrown) {
    return reference == null || thrown == null ? thrown : Withheld.of(thrown, 0);
  }

  /** A throwable reduced to its type and stack trace. */
  static final class Withheld extends RuntimeException {

    private static final int MAX_CAUSES = 16;

    private Withheld(String type, Throwable cause) {
      super(type + " (message withheld)", cause, false, true);
    }

    static Withheld of(Throwable thrown, int depth) {
      Throwable cause = thrown.getCause();
      Withheld withheld =
          new Withheld(
              thrown.getClass().getName(),
              cause == null || cause == thrown || depth >= MAX_CAUSES
                  ? null
                  : of(cause, depth + 1));
      withheld.setStackTrace(thrown.getStackTrace());
      return withheld;
    }
  }
}
