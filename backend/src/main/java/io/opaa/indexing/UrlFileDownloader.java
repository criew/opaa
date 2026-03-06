package io.opaa.indexing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads a single file from a URL to a temporary local path. */
public class UrlFileDownloader {

  private static final Logger log = LoggerFactory.getLogger(UrlFileDownloader.class);

  /**
   * Downloads a file from the given URL using the provided HTTP client and auth header. Returns the
   * path to a temporary file. The caller is responsible for deleting the temp file.
   */
  public Path download(HttpClient httpClient, String authHeader, String fileUrl, String fileName)
      throws IOException, InterruptedException {

    log.debug("Downloading: {}", fileUrl);

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder().uri(URI.create(fileUrl)).timeout(Duration.ofSeconds(120)).GET();

    if (authHeader != null) {
      reqBuilder.header("Authorization", authHeader);
    }

    // Preserve original extension for correct content-type detection
    String suffix = extractExtension(fileName);
    Path tempFile = Files.createTempFile("opaa-", suffix);

    HttpResponse<Path> response =
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofFile(tempFile));

    if (response.statusCode() != 200) {
      Files.deleteIfExists(tempFile);
      throw new IOException("HTTP " + response.statusCode() + " downloading: " + fileUrl);
    }

    log.debug("Downloaded {} to {}", fileUrl, tempFile);
    return tempFile;
  }

  private String extractExtension(String fileName) {
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex >= 0) {
      return fileName.substring(dotIndex);
    }
    return ".tmp";
  }
}
