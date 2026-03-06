package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UrlFileDownloaderTest {

  private final UrlFileDownloader downloader = new UrlFileDownloader();

  @Test
  void preservesFileExtension() throws IOException, InterruptedException {
    @SuppressWarnings("unchecked")
    HttpResponse<Path> response = mock(HttpResponse.class);
    HttpClient httpClient = mock(HttpClient.class);

    Path tempFile = Files.createTempFile("opaa-", ".pdf");
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(tempFile);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenAnswer(
            inv -> {
              // The handler creates the temp file, we return our mock response
              return response;
            });

    try {
      Path result =
          downloader.download(
              httpClient, null, "https://example.com/files/report.pdf", "report.pdf");
      assertThat(result.getFileName().toString()).endsWith(".pdf");
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void throwsOnNon200Status() throws IOException, InterruptedException {
    @SuppressWarnings("unchecked")
    HttpResponse<Path> response = mock(HttpResponse.class);
    HttpClient httpClient = mock(HttpClient.class);

    Path tempFile = Files.createTempFile("opaa-", ".txt");
    when(response.statusCode()).thenReturn(404);
    when(response.body()).thenReturn(tempFile);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    assertThatThrownBy(
            () ->
                downloader.download(
                    httpClient, null, "https://example.com/missing.txt", "missing.txt"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTP 404");
  }
}
