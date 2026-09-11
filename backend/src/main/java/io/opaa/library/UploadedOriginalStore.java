package io.opaa.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The storage of uploaded originals (ADR-0030): everything the application does with the bytes of
 * an {@code UPLOAD} document after they left the request and before they are served again. The
 * storage backend - a directory on this machine today, an object store later - is known to the
 * adapter alone; a caller only ever holds an {@link UploadedOriginalRef}.
 *
 * <p><b>Resolving a locator means three things, and every read and delete below does all three</b>
 * (ADR-0030, Entscheidung 3): the original belongs to the library it is asked for, links are
 * followed before that is decided, and it actually exists. A locator that fails any of them is
 * indistinguishable from one that fails another - that is what lets {@code
 * LibraryDocumentService#loadContent} answer the same 404 for a foreign, a tampered and a vanished
 * original.
 *
 * <p><b>An attachment row's synthetic locator resolves to "not there", and that is not an
 * error.</b> Attachment bytes are never stored (ADR-0022): {@code <parent>/<index>/<name>} lies
 * inside the right prefix but names no stored original. The read paths never reach the store with
 * one, but {@code deleteDocument} does - deleting an attachment row must therefore pass through
 * {@link #delete} as an ordinary "nothing to remove", never as a failure and never removing the
 * parent's original.
 */
public interface UploadedOriginalStore {

  /**
   * Takes {@code bytes} for a new original of {@code libraryId} under a freshly allocated name
   * ending in {@code extension} and returns the local working file they were written to. The caller
   * closes the stream. Nothing is left behind when accepting fails.
   */
  AcceptedUpload accept(UUID libraryId, String extension, InputStream bytes) throws IOException;

  /**
   * The original behind {@code ref} as servable content, or empty when it does not resolve - both
   * answer the caller's "no original available" alike. {@code declaredContentType} is the row's own
   * type; the store only fills in a type where it is missing.
   */
  Optional<DocumentContent> openForDownload(
      UploadedOriginalRef ref, String fileName, String declaredContentType);

  /**
   * Runs {@code action} on a local file holding the original's bytes, or returns empty without
   * running it when {@code ref} does not resolve. The file is only valid for the duration of the
   * call, and a copy the store had to make for it is removed on every exit - including one where
   * {@code action} ends in an exception, which reaches the caller unchanged. {@code action} must
   * not return {@code null}.
   */
  <T> Optional<T> withLocalFile(UploadedOriginalRef ref, Function<Path, T> action);

  /** Removes the original behind {@code ref}; one that does not resolve is left alone. */
  void delete(UploadedOriginalRef ref);

  /** Whether {@code ref} resolves to an original this store holds for {@code ref}'s library. */
  boolean belongsToLibrary(UploadedOriginalRef ref);

  /**
   * Hands every original this store holds in {@code libraryId}'s own storage area to {@code
   * visitor}, one at a time, in the store's own order and without ever holding the whole listing:
   * a large bucket is walked page by page. Exactly the originals a locator of this library would
   * resolve to are visited - what lies in the area but would not resolve (a link leading out, a
   * folder marker) is not - and each is reported under the locator {@link AcceptedUpload#store()}
   * would have returned for it, so a visited locator compares equal to the {@code file_path} of
   * the row that owns it. A library without a storage area yields nothing.
   *
   * @throws UploadStoreUnavailableException when the store cannot be listed right now
   */
  void forEachStoredOriginal(UUID libraryId, Consumer<StoredOriginal> visitor);

  /**
   * One original as the listing sees it.
   *
   * @param locator the value {@code documents.file_path} carries for this original
   * @param lastModified when the original was written, as the store records it
   * @param size in bytes
   */
  record StoredOriginal(String locator, Instant lastModified, long size) {}

  /**
   * Runs once at startup ({@code UploadPendingRecoveryRunner}) - after the web server is already
   * accepting requests, so an implementation must only touch what predates this process: a killed
   * process has no {@code finally}, so whatever it left behind - working files, local copies - is
   * removed here, and a store that cannot be reached is reported without failing the start
   * (ADR-0030, Entscheidung 7 and 9). Nothing to do for a store whose working file is the original.
   */
  default void recoverAfterRestart() {}

  /**
   * Bytes taken for an upload, and the local working file the rest of that upload runs on -
   * checksum, content type detection and the asynchronous parsing all read it. The filesystem
   * adapter's working file <em>is</em> the stored original; another backend hands out a local copy.
   *
   * <p><b>Accepting and storing are two steps</b> (ADR-0030, Entscheidung 2), and an adapter must
   * not collapse them: the content/extension check and the checksum deduplication run between them
   * and still reject the upload. Storing before them would put the bytes into the remote storage
   * only to remove them again on every rejected re-upload of a file that is already there - the
   * common case, not the rare one.
   *
   * <p>Its two exits are not the same: {@link #release()} ends the working file's life and keeps
   * the original, {@link #discard()} removes both. The asynchronous processing releases; a failing
   * upload discards, which includes releasing.
   */
  interface AcceptedUpload {

    /** The local file this upload's processing reads, valid until it is released or discarded. */
    Path workingFile();

    /** Makes the accepted file the library's original and returns the reference to it. */
    UploadedOriginalRef store() throws IOException;

    /** The processing is done: the working file is no longer needed, the original stays. */
    void release();

    /** The upload failed: whatever was stored and the working file both go. */
    void discard();
  }
}
