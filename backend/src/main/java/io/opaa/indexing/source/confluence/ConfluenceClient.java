package io.opaa.indexing.source.confluence;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.sourceaccess.BoundedDownloader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The port every Confluence run, the connection test and the space listing talk to (ADR-0023,
 * Entscheidung 2) - one adapter per {@link ConfluenceEdition}, created by {@link
 * ConfluenceClientFactory}. An instance is bound to one library's connection and is not
 * thread-safe.
 *
 * <p>Contract shared by both adapters and guarded by {@code ConfluenceClientContractTest}: listings
 * and searches return identifiers and metadata but never a body; pagination follows the instance's
 * own {@code _links.next} to the end, bounded, and refuses a link off its origin; {@code 429} with
 * {@code Retry-After} slows a call down instead of failing it; and no exception, cause or log line
 * ever carries the credentials.
 */
public interface ConfluenceClient {

  ConfluenceEdition edition();

  /**
   * Verifies the credentials with the cheapest authenticated call the edition offers. Data Center
   * serves an unknown or revoked token anonymously with HTTP 200, so every listing path calls this
   * first: an anonymous, empty listing is otherwise indistinguishable from a complete one, and a
   * full run must never mistake it for a deletion finding (ADR-0023, Entscheidung 4).
   *
   * @throws ConfluenceAccessException.Authentication when the instance rejects them
   * @throws ConfluenceAccessException.EditionMismatch when the instance does not answer like this
   *     client's edition
   */
  void verifyCredentials() throws ConfluenceAccessException, InterruptedException;

  /** Every space the credentials may see, fully paginated, in the instance's order. */
  List<ConfluenceSpace> listSpaces() throws ConfluenceAccessException, InterruptedException;

  /**
   * Every current page of {@code spaceKey}, fully paginated, without bodies.
   *
   * @throws ConfluenceAccessException.Forbidden when the instance refuses the space ({@code 403})
   * @throws ConfluenceAccessException.NotFound when the instance does not show the space - which
   *     Cloud also answers for a space the credentials may not see; callers treat both as "space
   *     not readable", never as "space empty"
   */
  List<ConfluencePageSummary> listPages(String spaceKey)
      throws ConfluenceAccessException, InterruptedException;

  /**
   * One page with body, ancestors and status. A trashed page comes back with {@link
   * ConfluencePageStatus#TRASHED} in both editions - the positive finding a deletion needs
   * (ADR-0023, Entscheidung 4). Empty when the instance answers {@code 404} for every status, which
   * means "gone" as much as "not readable with these credentials" and is therefore no finding.
   *
   * @throws ConfluenceAccessException.Forbidden when the instance says so explicitly ({@code 403})
   */
  Optional<ConfluencePage> fetchPage(String pageId)
      throws ConfluenceAccessException, InterruptedException;

  /** Attachments of a page, fully paginated, without contents. */
  List<ConfluenceAttachment> listAttachments(String pageId)
      throws ConfluenceAccessException, InterruptedException;

  /**
   * Downloads an attachment into a temporary file, bounded by {@code maxBytes}. A {@code 403}/
   * {@code 404} answer surfaces as {@link ConfluenceAccessException.Forbidden}/{@link
   * ConfluenceAccessException.NotFound}, not as "unreachable".
   *
   * @throws BoundedDownloader.AttachmentTooLargeException when the content exceeds {@code maxBytes}
   */
  BoundedDownloader.DownloadedFile downloadAttachment(
      ConfluenceAttachment attachment, long maxBytes)
      throws ConfluenceAccessException, InterruptedException;

  /** {@link #downloadAttachment(ConfluenceAttachment, long)} bounded by the configured maximum. */
  BoundedDownloader.DownloadedFile downloadAttachment(ConfluenceAttachment attachment)
      throws ConfluenceAccessException, InterruptedException;

  /**
   * The pages in {@code spaceKeys} modified at or after {@code since}, via CQL, fully paginated -
   * identifiers, titles, space keys and versions, never bodies, since the version is what lets a
   * caller skip an unchanged page before any body fetch. The window is sent as a relative {@code
   * now("-Nm")}, so the instance evaluates it in its own clock and time zone.
   */
  List<ConfluencePageSummary> searchPagesModifiedSince(Set<String> spaceKeys, Instant since)
      throws ConfluenceAccessException, InterruptedException;

  /** The title-free page URL for this edition - identity and citation link of a page document. */
  String pageUrl(String spaceKey, String pageId);

  /** Request statistics of this client since creation - feeds run metrics. */
  ConfluenceRequestMeter meter();
}
