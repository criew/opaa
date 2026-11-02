package io.opaa.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

/**
 * A local file handed to an upload path that takes a {@link MultipartFile}: the copy of a chat
 * attachment into a library goes through the library upload unchanged, checks included. Valid only
 * while {@code file} exists.
 */
final class StoredFileMultipartFile implements MultipartFile {

  private final Path file;
  private final String originalFilename;
  private final String contentType;

  StoredFileMultipartFile(Path file, String originalFilename, String contentType) {
    this.file = file;
    this.originalFilename = originalFilename;
    this.contentType = contentType;
  }

  @Override
  public String getName() {
    return "file";
  }

  @Override
  public String getOriginalFilename() {
    return originalFilename;
  }

  @Override
  public String getContentType() {
    return contentType;
  }

  @Override
  public boolean isEmpty() {
    return getSize() == 0;
  }

  @Override
  public long getSize() {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return 0;
    }
  }

  @Override
  public byte[] getBytes() throws IOException {
    return Files.readAllBytes(file);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return Files.newInputStream(file);
  }

  @Override
  public void transferTo(java.io.File dest) throws IOException {
    Files.copy(file, dest.toPath());
  }
}
