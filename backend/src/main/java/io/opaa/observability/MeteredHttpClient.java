package io.opaa.observability;

import io.opaa.compliance.DataFlowCarrier;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient} that records every request it sends in {@link OutboundMetrics}: the one
 * measuring point of the outbound calls over the JDK client (ADR-0071, Entscheidung 8). The time
 * runs until {@code send} returns, with a streamed body handler until the response headers. Every
 * attempt counts, a retry after {@code 429} as well. Configuration and lifecycle are the wrapped
 * client's; closing either closes both.
 */
@DataFlowCarrier
public final class MeteredHttpClient extends HttpClient {

  private final HttpClient delegate;
  private final OutboundMetrics metrics;
  private final Supplier<OutboundPathClass> pathClass;

  private MeteredHttpClient(
      HttpClient delegate, OutboundMetrics metrics, Supplier<OutboundPathClass> pathClass) {
    this.delegate = delegate;
    this.metrics = metrics;
    this.pathClass = pathClass;
  }

  /** {@code delegate} measured with the path class of the calling thread. */
  public static HttpClient of(HttpClient delegate, OutboundMetrics metrics) {
    return of(delegate, metrics, OutboundPathClass::ofCurrentThread);
  }

  /** {@code delegate} measured with a fixed path class. */
  public static HttpClient of(
      HttpClient delegate, OutboundMetrics metrics, OutboundPathClass pathClass) {
    return of(delegate, metrics, () -> pathClass);
  }

  /**
   * One measured view of a client per path class, for a caller that hands the request to a thread
   * of its own (a deadline executor) and decides the path class before.
   */
  public record ByPathClass(HttpClient interactive, HttpClient background) {

    public static ByPathClass of(HttpClient delegate, OutboundMetrics metrics) {
      return new ByPathClass(
          MeteredHttpClient.of(delegate, metrics, OutboundPathClass.INTERACTIVE),
          MeteredHttpClient.of(delegate, metrics, OutboundPathClass.BACKGROUND));
    }

    /**
     * The view for the path class of the calling thread ({@link
     * OutboundPathClass#ofCurrentThread}).
     */
    public HttpClient ofCallingThread() {
      return OutboundPathClass.ofCurrentThread() == OutboundPathClass.INTERACTIVE
          ? interactive
          : background;
    }
  }

  private static HttpClient of(
      HttpClient delegate, OutboundMetrics metrics, Supplier<OutboundPathClass> pathClass) {
    HttpClient unwrapped =
        delegate instanceof MeteredHttpClient metered ? metered.delegate : delegate;
    return new MeteredHttpClient(unwrapped, metrics, pathClass);
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    OutboundPathClass path = pathClass.get();
    long start = System.nanoTime();
    try {
      HttpResponse<T> response = delegate.send(request, handler);
      metrics.record(
          path,
          OutboundOutcome.ofStatus(response.statusCode(), response.headers()),
          System.nanoTime() - start);
      return response;
    } catch (IOException | InterruptedException | RuntimeException e) {
      metrics.record(path, OutboundOutcome.ofFailure(e), System.nanoTime() - start);
      throw e;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return measured(() -> delegate.sendAsync(request, handler));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return measured(() -> delegate.sendAsync(request, handler, pushPromiseHandler));
  }

  /**
   * Hands out the delegate's own future, so a caller's {@code cancel} still aborts the exchange; a
   * call its caller cancels (Spring's JDK request factory at its read timeout) counts when the
   * cancel lands.
   */
  private <T> CompletableFuture<HttpResponse<T>> measured(
      Supplier<CompletableFuture<HttpResponse<T>>> sending) {
    OutboundPathClass path = pathClass.get();
    long start = System.nanoTime();
    CompletableFuture<HttpResponse<T>> sent = sending.get();
    sent.whenComplete(
        (response, failure) -> {
          long nanos = System.nanoTime() - start;
          if (failure != null) {
            Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            metrics.record(path, OutboundOutcome.ofFailure(cause), nanos);
          } else {
            metrics.record(
                path, OutboundOutcome.ofStatus(response.statusCode(), response.headers()), nanos);
          }
        });
    return sent;
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    return delegate.newWebSocketBuilder();
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public boolean awaitTermination(Duration duration) throws InterruptedException {
    return delegate.awaitTermination(duration);
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public void shutdownNow() {
    delegate.shutdownNow();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
