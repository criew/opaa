package io.opaa.indexing.source;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Why a library's source is not reached now, who can change that, and the German notice a refused
 * run, a refused original and the library itself carry (spec "Konnektor-Freigabe und Sperre").
 *
 * @param responsible the German name of who is in charge ("Systemverwaltung", "Verwaltende der
 *     Bibliothek")
 * @param action what {@code responsible} does to lift it, {@code null} where only the system
 *     administration can
 * @param contentDeletedOn the day from which the content is erased, {@code null} where none is set
 */
public record SourceBlock(
    Reason reason, String responsible, String notice, Action action, LocalDate contentDeletedOn) {

  public SourceBlock {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(responsible, "responsible");
    Objects.requireNonNull(notice, "notice");
  }

  /** A block no action lifts but the system administration's. */
  public SourceBlock(Reason reason, String responsible, String notice) {
    this(reason, responsible, notice, null, null);
  }

  public SourceBlock(Reason reason, String responsible, String notice, Action action) {
    this(reason, responsible, notice, action, null);
  }

  /** What lifts a block, offered where the block names it. */
  public enum Action {
    /** The owner of a private library connects her account anew ("Verbundene Konten"). */
    CONNECT_OWN_ACCOUNT,
    /** The library's managers enter its secret anew or correct its address. */
    EDIT_SOURCE,
    /** The library's managers - or the owner of a private library - assign it to a profile. */
    ASSIGN_PROFILE
  }

  /**
   * Why a source is not reached. The order of declaration is the precedence: when several reasons
   * apply, the first one declared is the block. Every property of a reason is declared here.
   */
  public enum Reason {
    /** The system administration locked the library's connector type. */
    TYPE_LOCKED(true, false, true),
    /** The system administration locked the library's connection profile. */
    PROFILE_LOCKED(true, false, true),
    /**
     * The library's type is usable only through a profile, and the library still has its own
     * address; lifted by connecting it through a profile.
     */
    PROFILE_REQUIRED(true, false, true),
    /** The library's profile was deleted ("Zugang entfernt"). */
    ACCESS_REMOVED(false, true, true),
    /** The account of the person whose secret the library is reached with is deactivated. */
    OWNER_DEACTIVATED(false, true, true),
    /** That account rests: no sign-in for long, or its way of signing in is switched off. */
    DORMANT(false, true, true),
    /** The library's address does not lie under the server address of its profile. */
    TARGET_OUTSIDE_PROFILE(false, true, false),
    /** The profile asks for a secret the connection does not hold (yet or any more). */
    NOT_CONNECTED(false, true, true),
    /** The provider rejected the held secret, or it ran out; only connecting anew lifts it. */
    EXPIRED(false, true, true);

    private final boolean lock;
    private final boolean endsRunningRun;
    private final boolean shownInAnswer;

    Reason(boolean lock, boolean endsRunningRun, boolean shownInAnswer) {
      this.lock = lock;
      this.endsRunningRun = endsRunningRun;
      this.shownInAnswer = shownInAnswer;
    }

    /** A lock of the system administration: it lasts until lifted, and no scheduled run starts. */
    public boolean lock() {
      return lock;
    }

    /**
     * Also refuses the secret a running run asks for again and the validation of a change: the
     * connection itself is unusable, not only a new start.
     */
    public boolean endsRunningRun() {
      return endsRunningRun;
    }

    /** Marks a source in an answer as not updated ("Stand vom …"). */
    public boolean shownInAnswer() {
      return shownInAnswer;
    }
  }

  /** Whether the system administration locked the source: it stays blocked until lifted. */
  public boolean locked() {
    return reason.lock();
  }
}
