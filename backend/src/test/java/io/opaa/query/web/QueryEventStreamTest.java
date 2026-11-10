package io.opaa.query.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.QueryResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QueryEventStreamTest {

  private final HoldingOutput output = new HoldingOutput();
  private final QueryEventStream stream = new QueryEventStream(response(output), mapper());

  @Test
  void aPingDoesNotWaitBehindAWriteThatHangsOnTheReader() throws Exception {
    stream.started();
    output.holdNextWrite();
    Thread writer = Thread.ofPlatform().start(() -> provisional("Die Antwort"));
    assertThat(output.writeHeld.await(5, TimeUnit.SECONDS)).isTrue();

    Thread pinger = Thread.ofPlatform().start(this::ping);
    pinger.join(2_000);

    assertThat(pinger.isAlive()).as("the ping waited for the hanging write").isFalse();
    output.release.countDown();
    writer.join(5_000);
    assertThat(output.text()).doesNotContain(": ping");
  }

  @Test
  void aPingAfterTheClosingEventWritesNothing() throws Exception {
    stream.complete(new QueryResponse());
    int written = output.text().length();

    stream.ping();
    stream.idle();

    assertThat(output.text()).hasSize(written);
  }

  @Test
  void aPingBeforeTheStreamStartedWritesNothing() throws Exception {
    stream.ping();

    assertThat(output.text()).isEmpty();
  }

  @Test
  void aPingWhileNothingIsWrittenReachesTheReader() throws Exception {
    stream.started();

    stream.ping();

    assertThat(output.text()).endsWith(": ping\n\n");
  }

  private void provisional(String text) {
    try {
      stream.provisional(text, false);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void ping() {
    try {
      stream.ping();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonMapper mapper() {
    return QueryEventStream.mapper(JsonMapper.builder().build());
  }

  private static HttpServletResponse response(ServletOutputStream output) {
    HttpServletResponse response = mock(HttpServletResponse.class);
    try {
      when(response.getOutputStream()).thenReturn(output);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return response;
  }

  /** An output whose next write can be made to hang, as on a reader that stopped reading. */
  private static final class HoldingOutput extends ServletOutputStream {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final CountDownLatch writeHeld = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean holdNext;

    void holdNextWrite() {
      holdNext = true;
    }

    synchronized String text() {
      return bytes.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      if (holdNext) {
        holdNext = false;
        writeHeld.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      synchronized (this) {
        bytes.write(b, off, len);
      }
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {}
  }
}
