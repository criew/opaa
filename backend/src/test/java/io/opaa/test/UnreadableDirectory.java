package io.opaa.test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Makes a directory unlistable for the current process - POSIX permissions where supported (Linux
 * CI), a deny ACL entry otherwise (Windows/NTFS) - and restores it on {@link #close()}, so
 * {@code @TempDir} cleanup still works. The test is skipped (not passed) when the directory stays
 * listable, for example when running as root.
 */
public final class UnreadableDirectory implements AutoCloseable {

  private final Path directory;
  private final Set<PosixFilePermission> originalPosix;
  private final List<AclEntry> originalAcl;

  private UnreadableDirectory(
      Path directory, Set<PosixFilePermission> originalPosix, List<AclEntry> originalAcl) {
    this.directory = directory;
    this.originalPosix = originalPosix;
    this.originalAcl = originalAcl;
  }

  public static UnreadableDirectory of(Path directory) throws IOException {
    UnreadableDirectory handle;
    if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
      handle = new UnreadableDirectory(directory, Files.getPosixFilePermissions(directory), null);
      Files.setPosixFilePermissions(directory, Set.of());
    } else {
      AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
      assumeTrue(view != null, "needs POSIX permissions or ACLs to make a directory unreadable");
      List<AclEntry> original = view.getAcl();
      handle = new UnreadableDirectory(directory, null, original);
      List<AclEntry> denied = new ArrayList<>();
      denied.add(
          AclEntry.newBuilder()
              .setType(AclEntryType.DENY)
              .setPrincipal(view.getOwner())
              .setPermissions(AclEntryPermission.LIST_DIRECTORY)
              .build());
      denied.addAll(original);
      view.setAcl(denied);
    }
    if (isListable(directory)) {
      handle.close();
      assumeTrue(false, "needs a genuinely unreadable directory, so not as root");
    }
    return handle;
  }

  private static boolean isListable(Path directory) throws IOException {
    try (DirectoryStream<Path> ignored = Files.newDirectoryStream(directory)) {
      return true;
    } catch (AccessDeniedException e) {
      return false;
    }
  }

  @Override
  public void close() {
    try {
      if (originalPosix != null) {
        Files.setPosixFilePermissions(directory, originalPosix);
      } else {
        Files.getFileAttributeView(directory, AclFileAttributeView.class).setAcl(originalAcl);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
