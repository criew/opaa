package io.opaa.indexing.source.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileInternalInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Packet;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.mssmb2.messages.SMB2CreateRequest;
import com.hierynomus.protocol.transport.PacketHandlers;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.protocol.transport.TransportLayer;
import com.hierynomus.smb.SMBPacket;
import com.hierynomus.smb.SMBPacketData;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.auth.NtlmAuthenticator;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.Directory;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.Share;
import com.hierynomus.smbj.transport.TransportLayerFactory;
import com.hierynomus.smbj.transport.tcp.direct.DirectTcpTransportFactory;
import io.opaa.indexing.job.RequestBudgetExhaustedException;
import io.opaa.indexing.source.ConnectorChecks;
import io.opaa.indexing.source.RenewableCredential;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.net.SocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One signed-in share of one run or probe: listing, a single look-up and bounded downloads, every
 * SMB message charged to the {@link RequestBudget} before it leaves. The socket is opened only
 * after the target validation passed for the host of {@code sourceUrl}, signing is required,
 * encryption is used where the server offers it, and DFS referrals are not followed - credentials
 * never leave that host. A guest or anonymous session is refused.
 */
final class SmbShareClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SmbShareClient.class);

  static final String ALLOWLIST_HINT = TargetAddressValidator.ALLOWLIST_HINT;

  private static final long REPARSE_TAG_NAME_SURROGATE = 0x20000000L;
  private static final long REPARSE_TAG_DFS = 0x8000000AL;
  private static final long ATTRIBUTE_RECALL_ON_OPEN = 0x00040000L;
  private static final long ATTRIBUTE_RECALL_ON_DATA_ACCESS = 0x00400000L;

  private static final Set<Long> AUTHENTICATION_STATUSES =
      Set.of(
          NtStatus.STATUS_LOGON_FAILURE.getValue(),
          NtStatus.STATUS_ACCOUNT_DISABLED.getValue(),
          NtStatus.STATUS_PASSWORD_EXPIRED.getValue(),
          NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED.getValue(),
          0xC000006EL, // STATUS_ACCOUNT_RESTRICTION
          0xC000006FL, // STATUS_INVALID_LOGON_HOURS
          0xC0000070L, // STATUS_INVALID_WORKSTATION
          0xC0000193L, // STATUS_ACCOUNT_EXPIRED
          0xC0000224L, // STATUS_PASSWORD_MUST_CHANGE
          0xC0000234L); // STATUS_ACCOUNT_LOCKED_OUT

  /** The statuses that refuse the secret itself; the rest of a refused sign-in is a policy. */
  private static final Set<Long> SECRET_REJECTED_STATUSES =
      Set.of(
          NtStatus.STATUS_LOGON_FAILURE.getValue(),
          NtStatus.STATUS_PASSWORD_EXPIRED.getValue(),
          0xC0000064L, // STATUS_NO_SUCH_USER
          0xC000006AL, // STATUS_WRONG_PASSWORD
          0xC0000224L); // STATUS_PASSWORD_MUST_CHANGE

  private static final Set<Long> NOT_FOUND_STATUSES =
      Set.of(
          NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue(),
          NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.getValue(),
          NtStatus.STATUS_NO_SUCH_FILE.getValue(),
          NtStatus.STATUS_NOT_FOUND.getValue(),
          NtStatus.STATUS_DELETE_PENDING.getValue(),
          NtStatus.STATUS_FILE_DELETED.getValue(),
          NtStatus.STATUS_NOT_A_DIRECTORY.getValue(),
          NtStatus.STATUS_FILE_IS_A_DIRECTORY.getValue());

  /** NTLM is switched off on the server or the domain. */
  static final long STATUS_NTLM_BLOCKED = 0xC0000418L;

  private static final Set<SMB2ShareAccess> SHARE_ALL = EnumSet.allOf(SMB2ShareAccess.class);

  /** Links smbj may follow within one open before it counts as a loop. */
  static final int MAX_LINK_HOPS = 16;

  private static final int FSCTL_GET_REPARSE_POINT = 0x000900A8;

  /** The CREATE requests the current thread sent for the open in progress, {@code null} outside. */
  static final ThreadLocal<int[]> OPENING = new ThreadLocal<>();

  private final SmbAddress address;

  /** Asked on every sign-in, so a session set up anew uses the credentials valid then. */
  private final RenewableCredential<SmbCredentials> credentials;

  private final RequestBudget budget;
  private final BudgetedTransportFactory transport;
  private final SMBClient client;

  /** Counts the connections dropped by {@link #reset}. */
  private final AtomicInteger connections = new AtomicInteger();

  /** Downloads repeated because their connection was dropped - observable for tests. */
  final AtomicInteger retriedAfterReset = new AtomicInteger();

  private Connection connection;
  private Session session;
  private SmbCredentials signedInWith;
  private volatile DiskShare share;
  private SmbAccessException failure;

  /** One entry of a folder as the server lists it. */
  record Item(
      String name,
      boolean directory,
      long size,
      long lastWriteTicks,
      long attributes,
      long reparseTag,
      long fileId) {

    /** A symbolic link, junction, mount point or DFS link - never followed. */
    boolean link() {
      return (attributes & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0
          && isLinkTag(reparseTag);
    }

    /**
     * A name no Windows server gives: a path separator, a control character or {@code ..} would
     * make the name a path of its own.
     */
    boolean unusableName() {
      return name.isEmpty()
          || name.equals("..")
          || name.chars().anyMatch(c -> c < 0x20 || c == '/' || c == '\\' || c == ':');
    }

    /** Moved to other storage; reading it would recall it first. */
    boolean offline() {
      return (attributes
              & (FileAttributes.FILE_ATTRIBUTE_OFFLINE.getValue()
                  | ATTRIBUTE_RECALL_ON_OPEN
                  | ATTRIBUTE_RECALL_ON_DATA_ACCESS))
          != 0;
    }

    private static Item of(FileIdBothDirectoryInformation info) {
      long attributes = info.getFileAttributes();
      boolean reparse = (attributes & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0;
      return new Item(
          info.getFileName(),
          (attributes & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0,
          info.getEndOfFile(),
          info.getLastWriteTime().getWindowsTimeStamp(),
          attributes,
          // a reparse point carries its tag where the extended-attribute size would be
          reparse ? info.getEaSize() : 0,
          info.getFileId());
    }
  }

  private SmbShareClient(
      SmbAddress address,
      RenewableCredential<SmbCredentials> credentials,
      RequestBudget budget,
      SmbConfig.Builder config) {
    this.address = address;
    this.credentials = credentials;
    this.budget = budget;
    this.transport = new BudgetedTransportFactory(budget);
    this.client = new SMBClient(config.withTransportLayerFactory(transport).build());
  }

  /** A client for the share that connects on its first request. */
  static SmbShareClient of(
      SmbAddress address,
      SmbCredentials credentials,
      TargetAddressValidator targetAddressValidator,
      RequestBudget budget,
      Duration timeout) {
    return of(
        address, RenewableCredential.fixed(credentials), targetAddressValidator, budget, timeout);
  }

  /** As below, without a renewal after a rejected sign-in. */
  static SmbShareClient of(
      SmbAddress address,
      Supplier<SmbCredentials> credentials,
      TargetAddressValidator targetAddressValidator,
      RequestBudget budget,
      Duration timeout) {
    return of(
        address,
        new RenewableCredential<SmbCredentials>() {
          @Override
          public SmbCredentials get() {
            return credentials.get();
          }

          @Override
          public boolean renewedAfterRejection(SmbCredentials sent) {
            return false;
          }
        },
        targetAddressValidator,
        budget,
        timeout);
  }

  /**
   * A client for the share that connects on its first request and signs in with what {@code
   * credentials} answers at each sign-in; what it throws ends that request. A sign-in whose secret
   * the server rejected is repeated once when {@code credentials} was renewed meanwhile.
   */
  static SmbShareClient of(
      SmbAddress address,
      RenewableCredential<SmbCredentials> credentials,
      TargetAddressValidator targetAddressValidator,
      RequestBudget budget,
      Duration timeout) {
    SmbConfig.Builder config =
        SmbConfig.builder()
            .withAuthenticators(new NtlmAuthenticator.Factory())
            .withSigningRequired(true)
            .withEncryptData(true)
            .withDfsEnabled(false)
            .withDirectoryLeasingEnabled(false)
            .withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .withSocketFactory(new ValidatingSocketFactory(targetAddressValidator, timeout));
    return new SmbShareClient(address, credentials, budget, config);
  }

  /**
   * Connects to the share's host, signs in and opens the share, unless that already happened.
   *
   * @throws SmbAccessException.Authentication for a refused or guest sign-in
   * @throws SmbAccessException.ShareNotFound when the server has no such share
   * @throws SmbAccessException.AccessDenied when the account may not open the share
   * @throws SmbAccessException.Unreachable when the host is blocked, unreachable or no file server
   */
  synchronized void connect() throws SmbAccessException, InterruptedException {
    if (share != null) {
      return;
    }
    if (failure != null) {
      throw failure;
    }
    try {
      try {
        signIn();
      } catch (SmbAccessException.Authentication e) {
        if (!e.secretRejected() || !credentials.renewedAfterRejection(signedInWith)) {
          throw e;
        }
        closeSessionAndConnection();
        session = null;
        connection = null;
        signIn();
      }
    } catch (SmbAccessException e) {
      failure = e;
      throw e;
    }
  }

  private DiskShare share() throws SmbAccessException, InterruptedException {
    connect();
    return share;
  }

  private void signIn() throws SmbAccessException, InterruptedException {
    SmbCredentials credentials = this.credentials.get();
    signedInWith = credentials;
    try {
      connection = client.connect(address.socketHost(), address.port());
    } catch (TargetAddressValidator.UnknownTargetHostException e) {
      throw new SmbAccessException.Unreachable(e.getMessage());
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      throw new SmbAccessException.Unreachable(e.getMessage() + " " + ALLOWLIST_HINT);
    } catch (IOException | RuntimeException e) {
      throw translate(e, "den Server „" + address.host() + "“");
    }
    try {
      session =
          connection.authenticate(
              new AuthenticationContext(
                  credentials.username(),
                  credentials.password().toCharArray(),
                  credentials.domain()));
    } catch (RuntimeException e) {
      throw signInFailure(e);
    }
    if (session.isGuest() || session.isAnonymous()) {
      throw guestOnly(credentials);
    }
    Share opened;
    try {
      opened = session.connectShare(address.share());
    } catch (RuntimeException e) {
      throw translate(e, "die Freigabe „" + address.share() + "“");
    }
    if (!(opened instanceof DiskShare disk)) {
      closeQuietly(opened);
      throw new SmbAccessException.Unreachable(
          "„" + address.share() + "“ ist keine Dateifreigabe (etwa ein Drucker).");
    }
    share = disk;
  }

  /**
   * The finding of a refused session setup: always a sign-in finding, never "signed in, but",
   * unless the server could not be reached at all.
   */
  SmbAccessException signInFailure(RuntimeException e) throws InterruptedException {
    SmbCredentials credentials = signedInWith != null ? signedInWith : this.credentials.get();
    if (transport.refused != null) {
      throw transport.refused;
    }
    if (isGuestRefusal(e)) {
      return guestOnly(credentials);
    }
    SMBApiException api = find(e, SMBApiException.class);
    if (api != null && api.getStatusCode() == STATUS_NTLM_BLOCKED) {
      return new SmbAccessException.Authentication(
          "Der Server „"
              + address.host()
              + "“ lässt keine Anmeldung mit NTLM zu. OPAA unterstützt Kerberos noch nicht; der"
              + " Server bzw. die Domäne muss NTLM für das Dienstkonto erlauben.");
    }
    SmbAccessException translated = translate(e, "die Anmeldung");
    if (translated instanceof SmbAccessException.Unreachable) {
      return translated;
    }
    boolean secretRejected = api != null && SECRET_REJECTED_STATUSES.contains(api.getStatusCode());
    if (translated instanceof SmbAccessException.AccessDenied) {
      return new SmbAccessException.Authentication(
          "Der Server „"
              + address.host()
              + "“ hat die Anmeldung von „"
              + credentials.account()
              + "“ verweigert (Zugriff verweigert). Dem Konto fehlt das Recht, sich über das"
              + " Netzwerk am Server anzumelden.");
    }
    return refusedSignIn(credentials, secretRejected);
  }

  SourceRequestMeter meter() {
    return budget.meter();
  }

  /**
   * The entries of the folder {@code path} ({@code ""} for the share's root), fetched batch by
   * batch as the caller iterates; {@code .} and {@code ..} are left out. The caller closes the
   * listing.
   */
  Listing list(String path) throws SmbAccessException, InterruptedException {
    return list(path, null);
  }

  /** The entry {@code path} names, empty when neither it nor its folder exists. */
  Optional<Item> find(String path) throws SmbAccessException, InterruptedException {
    int slash = path.lastIndexOf('/');
    String parent = slash < 0 ? "" : path.substring(0, slash);
    String name = path.substring(slash + 1);
    try (Listing listing = list(parent, name)) {
      while (listing.hasNext()) {
        Item item = listing.next();
        if (item.name().equals(name)) {
          return Optional.of(item);
        }
      }
      return Optional.empty();
    } catch (SmbAccessException.NotFound e) {
      return Optional.empty();
    } catch (ListingFailure e) {
      if (e.failure() instanceof SmbAccessException.NotFound) {
        return Optional.empty();
      }
      throw e.failure();
    }
  }

  private Listing list(String path, String pattern)
      throws SmbAccessException, InterruptedException {
    String what = path.isEmpty() ? "den Stammordner der Freigabe" : "den Ordner „" + path + "“";
    Directory directory = (Directory) openUnlinked(path, true, what);
    try {
      return new Listing(
          directory, directory.iterator(FileIdBothDirectoryInformation.class, pattern), what);
    } catch (RuntimeException e) {
      closeQuietly(directory);
      throw translate(e, what);
    }
  }

  /**
   * Copies the file {@code path} into a temporary file <b>the caller deletes</b>, refusing it as
   * soon as it grows past {@code maxBytes}; a partial file never survives.
   */
  Path download(String path, String fileName, long maxBytes)
      throws SmbAccessException, InterruptedException {
    int generation = connections.get();
    try {
      return downloadOnce(path, fileName, maxBytes);
    } catch (SmbAccessException e) {
      if (connections.get() == generation || e instanceof SmbAccessException.Link) {
        throw e;
      }
      // the connection was dropped under this download (a link loop elsewhere): once more anew
      retriedAfterReset.incrementAndGet();
      return downloadOnce(path, fileName, maxBytes);
    }
  }

  private Path downloadOnce(String path, String fileName, long maxBytes)
      throws SmbAccessException, InterruptedException {
    String what = "die Datei „" + path + "“";
    File file = (File) openUnlinked(path, false, what);
    Path target = null;
    try (file) {
      target = Files.createTempFile("opaa-smb-", suffixOf(fileName));
      long written = 0;
      try (InputStream in = file.getInputStream();
          OutputStream out = Files.newOutputStream(target)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
          written += read;
          if (written > maxBytes) {
            throw new SmbAccessException.TooLarge(
                "Die Datei „" + fileName + "“ ist größer als " + maxBytes + " Bytes.");
          }
          out.write(buffer, 0, read);
        }
      }
      budget.meter().recordBytes(written);
      Path done = target;
      target = null;
      return done;
    } catch (IOException | RuntimeException e) {
      throw translate(e, what);
    } finally {
      if (target != null) {
        deleteQuietly(target);
      }
    }
  }

  /**
   * Opens {@code path} without following a link at its end: the last component is opened as the
   * link itself and refused when it is a symbolic link, junction or DFS link; any other reparse
   * point (a deduplicated file) is opened again as usual. A link earlier in the path is followed by
   * smbj at most {@link #MAX_LINK_HOPS} times, then refused as a loop.
   */
  private DiskEntry openUnlinked(String path, boolean directory, String what)
      throws SmbAccessException, InterruptedException {
    DiskEntry entry = open(path, directory, true, what);
    long tag;
    try {
      long attributes = entry.getFileInformation(FileBasicInformation.class).getFileAttributes();
      if ((attributes & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) == 0) {
        return entry;
      }
      tag = reparseTag(entry);
    } catch (RuntimeException e) {
      closeQuietly(entry);
      throw translate(e, what);
    }
    closeQuietly(entry);
    if (isLinkTag(tag)) {
      throw new SmbAccessException.Link(
          capitalize(what) + " ist eine Verknüpfung; OPAA folgt Verknüpfungen nicht.");
    }
    return open(path, directory, false, what);
  }

  private DiskEntry open(String path, boolean directory, boolean reparsePoint, String what)
      throws SmbAccessException, InterruptedException {
    DiskShare disk = share();
    EnumSet<SMB2CreateOptions> options =
        EnumSet.of(
            directory
                ? SMB2CreateOptions.FILE_DIRECTORY_FILE
                : SMB2CreateOptions.FILE_NON_DIRECTORY_FILE);
    if (reparsePoint) {
      options.add(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT);
    }
    int[] hops = new int[1];
    OPENING.set(hops);
    try {
      return directory
          ? disk.openDirectory(
              path,
              EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
              null,
              SHARE_ALL,
              SMB2CreateDisposition.FILE_OPEN,
              options)
          : disk.openFile(
              path,
              EnumSet.of(AccessMask.GENERIC_READ),
              null,
              SHARE_ALL,
              SMB2CreateDisposition.FILE_OPEN,
              options);
    } catch (RuntimeException e) {
      if (hops[0] > MAX_LINK_HOPS + 1) {
        // the refused request left a gap in the message sequence: start over on a new connection
        reset();
        throw new SmbAccessException.Link(
            capitalize(what)
                + " führt über mehr als "
                + MAX_LINK_HOPS
                + " Verknüpfungen (Schleife) und wird nicht gelesen.");
      }
      throw translate(e, what);
    } finally {
      OPENING.remove();
    }
  }

  /**
   * The reparse tag of an entry opened as a reparse point, {@code 0} when the server tells none.
   */
  private static long reparseTag(DiskEntry entry) {
    try {
      byte[] data = entry.ioctl(FSCTL_GET_REPARSE_POINT, true, new byte[0], 0, 0);
      return data.length < 4
          ? 0
          : ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    } catch (SMBApiException e) {
      return 0;
    }
  }

  static boolean isLinkTag(long tag) {
    return (tag & REPARSE_TAG_NAME_SURROGATE) != 0 || tag == REPARSE_TAG_DFS;
  }

  /**
   * Drops the connection; the next request signs in again. A request of another thread that fails
   * on the dropped connection is repeated once ({@link #download}) or ends only its own folder.
   */
  synchronized void reset() {
    connections.incrementAndGet();
    DiskShare current = share;
    share = null;
    closeQuietly(current);
    closeSessionAndConnection();
    session = null;
    connection = null;
  }

  @Override
  public void close() {
    closeQuietly(share);
    closeSessionAndConnection();
    client.close();
  }

  private void closeSessionAndConnection() {
    if (session != null) {
      try {
        session.close();
      } catch (IOException | RuntimeException e) {
        log.debug("Closing the SMB session to {} failed: {}", address.host(), e.toString());
      }
    }
    if (connection != null) {
      try {
        connection.close(true);
      } catch (IOException | RuntimeException e) {
        log.debug("Closing the SMB connection to {} failed: {}", address.host(), e.toString());
      }
    }
  }

  /** A folder's entries, fetched as they are iterated. */
  final class Listing implements Iterator<Item>, AutoCloseable {

    private final Directory directory;
    private final String what;
    private final Iterator<FileIdBothDirectoryInformation> iterator;
    private final int generation = connections.get();
    private Item next;

    private Listing(
        Directory directory, Iterator<FileIdBothDirectoryInformation> iterator, String what) {
      this.directory = directory;
      this.iterator = iterator;
      this.what = what;
    }

    /**
     * @throws SmbAccessException wrapped in {@link ListingFailure} when the next batch fails
     */
    @Override
    public boolean hasNext() {
      while (next == null) {
        FileIdBothDirectoryInformation info;
        try {
          if (!iterator.hasNext()) {
            return false;
          }
          info = iterator.next();
        } catch (RuntimeException e) {
          if (connections.get() != generation) {
            // the connection was dropped under this listing: this folder only, not the run
            throw new ListingFailure(
                new SmbAccessException.Transient(
                    capitalize(what) + " wurde während einer Neuverbindung nicht fertig gelesen."));
          }
          throw new ListingFailure(translateUnchecked(e, what));
        }
        String name = info.getFileName();
        if (!name.equals(".") && !name.equals("..")) {
          next = Item.of(info);
        }
      }
      return true;
    }

    @Override
    public Item next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      Item item = next;
      next = null;
      return item;
    }

    /** The listed folder's own file id, {@code 0} when the server tells none. */
    long folderId() throws SmbAccessException, InterruptedException {
      try {
        return directory.getFileInformation(FileInternalInformation.class).getIndexNumber();
      } catch (RuntimeException e) {
        SmbAccessException failure = translate(e, what);
        if (failure instanceof SmbAccessException.Transient) {
          return 0;
        }
        throw failure;
      }
    }

    @Override
    public void close() {
      closeQuietly(directory);
    }
  }

  /** A failed batch of a {@link Listing}, carrying the translated failure. */
  static final class ListingFailure extends RuntimeException {
    private final transient SmbAccessException failure;

    ListingFailure(SmbAccessException failure) {
      super(failure.getMessage(), null, false, false);
      this.failure = failure;
    }

    SmbAccessException failure() {
      return failure;
    }
  }

  private SmbAccessException translateUnchecked(RuntimeException e, String what) {
    try {
      return translate(e, what);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new SmbAccessException.Transient("Der Lauf wurde unterbrochen.");
    }
  }

  /**
   * The kind of a failed request, as a German sentence about {@code what}. A request the budget
   * refused rethrows its {@link RequestBudgetExhaustedException}; an interrupt is rethrown.
   */
  private SmbAccessException translate(Exception e, String what) throws InterruptedException {
    if (transport.refused != null) {
      throw transport.refused;
    }
    if (e instanceof SmbAccessException access) {
      return access;
    }
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw new InterruptedException("SMB request interrupted");
      }
    }
    log.debug("SMB request for {} on {} failed: {}", what, address.host(), e.toString());
    SMBApiException api = find(e, SMBApiException.class);
    if (api != null) {
      long status = api.getStatusCode();
      if (AUTHENTICATION_STATUSES.contains(status)) {
        return new SmbAccessException.Authentication(
            "Der Server „" + address.host() + "“ hat die Anmeldung abgelehnt.",
            SECRET_REJECTED_STATUSES.contains(status));
      }
      if (status == NtStatus.STATUS_BAD_NETWORK_NAME.getValue()) {
        return new SmbAccessException.ShareNotFound(
            "Die Freigabe „"
                + address.share()
                + "“ gibt es auf dem Server „"
                + address.host()
                + "“ nicht.");
      }
      if (status == NtStatus.STATUS_ACCESS_DENIED.getValue()) {
        return new SmbAccessException.AccessDenied(
            "Das Dienstkonto darf " + what + " nicht lesen (Zugriff verweigert).");
      }
      if (NOT_FOUND_STATUSES.contains(status)) {
        return new SmbAccessException.NotFound(capitalize(what) + " gibt es nicht (mehr).");
      }
      if (status == NtStatus.STATUS_PATH_NOT_COVERED.getValue()
          || status == NtStatus.STATUS_DFS_UNAVAILABLE.getValue()) {
        return new SmbAccessException.Link(
            capitalize(what)
                + " liegt hinter einem DFS-Verweis. OPAA folgt DFS-Verweisen nicht; ist die Freigabe"
                + " ein DFS-Namensraum, bitte die Zielfreigabe direkt angeben.");
      }
      if (status == NtStatus.STATUS_SHARING_VIOLATION.getValue()
          || status == NtStatus.STATUS_FILE_LOCK_CONFLICT.getValue()) {
        return new SmbAccessException.Transient(
            capitalize(what) + " ist von einem anderen Programm gesperrt.");
      }
      if (status == NtStatus.STATUS_NETWORK_SESSION_EXPIRED.getValue()
          || status == NtStatus.STATUS_USER_SESSION_DELETED.getValue()
          || status == NtStatus.STATUS_NETWORK_NAME_DELETED.getValue()) {
        return new SmbAccessException.Unreachable(
            "Der Server „" + address.host() + "“ hat die Sitzung beendet.");
      }
      return new SmbAccessException.Transient(
          "Der Server hat die Anfrage für "
              + what
              + " abgelehnt (Status "
              + (api.getStatus() == NtStatus.STATUS_OTHER
                  ? String.format("0x%08X", status)
                  : api.getStatus().name())
              + ").");
    }
    if (find(e, TimeoutException.class) != null || find(e, SocketTimeoutException.class) != null) {
      return new SmbAccessException.Unreachable(
          "Der Server „" + address.host() + "“ hat nicht rechtzeitig geantwortet.");
    }
    IOException io = e instanceof IOException direct ? direct : find(e, IOException.class);
    if (io != null && !(io instanceof TransportException)) {
      return new SmbAccessException.Unreachable(
          ConnectorChecks.translateConnectionError(io) + " (Server „" + address.host() + "“)");
    }
    if (e instanceof IOException) {
      return new SmbAccessException.Transient(
          "Lesen von " + what + " ist fehlgeschlagen; die Verbindung brach ab.");
    }
    return new SmbAccessException.Unreachable(
        "Die Verbindung zum Server „"
            + address.host()
            + "“ ist gescheitert (SMB 2 oder 3 mit Signatur erforderlich).");
  }

  private static boolean isGuestRefusal(RuntimeException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause.getClass().getSimpleName().equals("SMB2GuestSigningRequiredException")) {
        return true;
      }
    }
    return false;
  }

  private SmbAccessException.Authentication guestOnly(SmbCredentials credentials) {
    return new SmbAccessException.Authentication(
        "Der Server „"
            + address.host()
            + "“ hat „"
            + credentials.account()
            + "“ nur als Gast angemeldet; Benutzername oder Passwort stimmen nicht.",
        true);
  }

  private SmbAccessException.Authentication refusedSignIn(
      SmbCredentials credentials, boolean secretRejected) {
    return new SmbAccessException.Authentication(
        "Der Server „"
            + address.host()
            + "“ hat die Anmeldung von „"
            + credentials.account()
            + "“ abgelehnt. Benutzername, Domäne und Passwort prüfen; das Konto darf nicht gesperrt"
            + " oder abgelaufen sein.",
        secretRejected);
  }

  private static <T extends Throwable> T find(Throwable e, Class<T> type) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return type.cast(cause);
      }
    }
    return null;
  }

  private static String capitalize(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  private static String suffixOf(String fileName) {
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || fileName.length() - dot > 16) {
      return ".tmp";
    }
    String suffix = fileName.substring(dot);
    return suffix.chars().allMatch(c -> c == '.' || Character.isLetterOrDigit(c)) ? suffix : ".tmp";
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete temp file {}", path);
    }
  }

  private void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception e) {
      log.debug("Closing an SMB handle on {} failed: {}", address.host(), e.toString());
    }
  }

  /**
   * Charges every outgoing SMB message to the budget; a refused one is never written, and the
   * refusal is kept so the caller rethrows it instead of a transport failure.
   */
  static final class BudgetedTransportFactory
      implements TransportLayerFactory<SMBPacketData<?>, SMBPacket<?, ?>> {

    private final RequestBudget budget;
    private final TransportLayerFactory<SMBPacketData<?>, SMBPacket<?, ?>> delegate;
    private volatile RequestBudgetExhaustedException refused;

    private BudgetedTransportFactory(RequestBudget budget) {
      this(budget, new DirectTcpTransportFactory<>());
    }

    BudgetedTransportFactory(
        RequestBudget budget, TransportLayerFactory<SMBPacketData<?>, SMBPacket<?, ?>> delegate) {
      this.budget = budget;
      this.delegate = delegate;
    }

    @Override
    public TransportLayer<SMBPacket<?, ?>> createTransportLayer(
        PacketHandlers<SMBPacketData<?>, SMBPacket<?, ?>> handlers, SmbConfig config) {
      TransportLayer<SMBPacket<?, ?>> inner = delegate.createTransportLayer(handlers, config);
      return new TransportLayer<>() {
        @Override
        public void write(SMBPacket<?, ?> packet) throws TransportException {
          if (refused != null) {
            throw new TransportException("request budget spent");
          }
          int[] hops = OPENING.get();
          if (hops != null
              && packet instanceof SMB2Packet smb2
              && smb2.getPacket() instanceof SMB2CreateRequest
              && ++hops[0] > MAX_LINK_HOPS + 1) {
            throw new TransportException("too many links followed");
          }
          try {
            budget.charge();
          } catch (RequestBudgetExhaustedException e) {
            refused = e;
            throw new TransportException("request budget spent");
          }
          inner.write(packet);
        }

        @Override
        public void connect(InetSocketAddress remoteAddress) throws IOException {
          inner.connect(remoteAddress);
        }

        @Override
        public void disconnect() throws IOException {
          inner.disconnect();
        }

        @Override
        public boolean isConnected() {
          return inner.isConnected();
        }
      };
    }
  }

  /**
   * Opens every socket of the client: the host passes the target validation first, then the
   * connection is made within {@code timeout}.
   */
  private static final class ValidatingSocketFactory extends SocketFactory {

    private final TargetAddressValidator validator;
    private final int timeoutMillis;

    private ValidatingSocketFactory(TargetAddressValidator validator, Duration timeout) {
      this.validator = validator;
      this.timeoutMillis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      validator.validateHost(host);
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        return socket;
      } catch (IOException e) {
        socket.close();
        throw e;
      }
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException {
      return createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      return createSocket(host.getHostAddress(), port);
    }

    @Override
    public Socket createSocket(
        InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
      return createSocket(address.getHostAddress(), port);
    }
  }
}
