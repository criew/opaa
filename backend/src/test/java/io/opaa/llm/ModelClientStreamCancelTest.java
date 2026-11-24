package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.instance.InvalidationBus;
import io.opaa.security.SettingsEncryptor;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.Disposable;

/**
 * Disposing the streamed answer of the chat client, built exactly as {@link
 * ActiveChatModelResolver} builds it, closes the connection to the model: a model that keeps
 * writing chunks sees its reader gone within seconds, instead of generating the rest of the answer
 * for nobody. A streamed answer that ends regularly leaves its connection open for the next call.
 */
class ModelClientStreamCancelTest {

  private static final Duration CHUNK_INTERVAL = Duration.ofMillis(500);
  private static final int MAX_CHUNKS = 60;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final ExecutorService serverThreads = Executors.newCachedThreadPool();
  private final CountDownLatch firstChunkWritten = new CountDownLatch(1);
  private final CountDownLatch readerGone = new CountDownLatch(1);
  private final Set<Integer> clientPorts = ConcurrentHashMap.newKeySet();
  private volatile int chunksPerAnswer = MAX_CHUNKS;
  private volatile Duration chunkInterval = CHUNK_INTERVAL;
  private HttpServer model;

  @BeforeEach
  void startModel() throws IOException {
    model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    model.setExecutor(serverThreads);
    model.createContext(
        "/v1/chat/completions",
        exchange -> {
          clientPorts.add(exchange.getRemoteAddress().getPort());
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          try {
            for (int index = 0; index < chunksPerAnswer; index++) {
              out.write(chunk(index).getBytes(StandardCharsets.UTF_8));
              out.flush();
              firstChunkWritten.countDown();
              Thread.sleep(chunkInterval.toMillis());
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.close();
          } catch (IOException e) {
            readerGone.countDown();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    model.start();
  }

  @AfterEach
  void stopModel() {
    model.stop(0);
    serverThreads.shutdownNow();
  }

  // regression guard for #3855: a body closed while another thread reads it must still end the
  // connection - OkHttp's own close fails then and leaves the model writing for nobody
  @Test
  void disposingTheStreamClosesTheConnectionToTheModel() throws InterruptedException {
    ChatClient client = chatResolver().resolveChatClient();
    CountDownLatch firstChunkRead = new CountDownLatch(1);

    Disposable subscription =
        client.prompt("Frage").stream()
            .chatResponse()
            .subscribe(chunk -> firstChunkRead.countDown(), failure -> {});
    assertThat(firstChunkWritten.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(firstChunkRead.await(10, TimeUnit.SECONDS)).isTrue();
    // The SDK's reader thread now waits inside a read for the next chunk; the dispose closes the
    // body from this thread meanwhile, as a cancel in the chat does.
    Thread.sleep(CHUNK_INTERVAL.toMillis() / 5);
    subscription.dispose();

    assertThat(readerGone.await(5, TimeUnit.SECONDS))
        .as("the model's connection is closed once the stream is disposed")
        .isTrue();
  }

  // regression guard for #3855: closing a regularly ended stream must not cancel the call - the
  // cancel would drop the keep-alive connection and every answer would open a new one
  @Test
  void aRegularlyEndedStreamLeavesItsConnectionForTheNextCall() {
    chunksPerAnswer = 3;
    chunkInterval = Duration.ZERO;
    ChatClient client = chatResolver().resolveChatClient();

    for (int answer = 0; answer < 3; answer++) {
      assertThat(client.prompt("Frage").stream().content().collectList().block(TIMEOUT))
          .containsExactly("Teil 0 ", "Teil 1 ", "Teil 2 ");
    }

    assertThat(clientPorts)
        .as("the three streamed answers share one connection to the model")
        .hasSize(1);
  }

  private static String chunk(int index) {
    String delta =
        index == 0
            ? "{\"role\":\"assistant\",\"content\":\"Teil 0 \"}"
            : "{\"content\":\"Teil " + index + " \"}";
    return "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
        + "\"created\":1700000000,\"model\":\"model-s\",\"choices\":[{\"index\":0,\"delta\":"
        + delta
        + ",\"finish_reason\":null}]}\n\n";
  }

  private ActiveChatModelResolver chatResolver() {
    LlmModelRepository repository = mock(LlmModelRepository.class);
    String baseUrl = "http://127.0.0.1:" + model.getAddress().getPort() + "/v1";
    when(repository.findAllByActiveTrue())
        .thenReturn(
            List.of(new LlmModel("Modell S", baseUrl, "model-s", BigDecimal.ZERO, 256, null)));
    return new ActiveChatModelResolver(
        repository,
        mock(SettingsEncryptor.class),
        provider(ObservationRegistry.class),
        provider(MeterRegistry.class),
        mock(InvalidationBus.class));
  }

  private static <T> ObjectProvider<T> provider(Class<T> type) {
    return new StaticListableBeanFactory().getBeanProvider(type);
  }
}
