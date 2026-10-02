package io.opaa.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/**
 * A {@link HttpClient} for validated targets: the {@code java.net.http} API its callers already
 * speak, carried by Apache HttpClient 5 with a {@link CheckedDnsResolver}. The target name is
 * resolved once, when the connection is opened; that answer is checked by the {@link
 * ConnectionAddressResolver} and is the one the socket connects to. TLS (SNI, hostname
 * verification) still runs against the name. A refused address surfaces as {@link
 * TargetAddressValidator.TargetAddressBlockedException}.
 *
 * <p>Behaves like the JDK client it replaces: HTTP/1.1, no redirects, no cookies, no transparent
 * decompression, no automatic retries, the default {@link ProxySelector} unless a proxy is given.
 * {@link HttpRequest#timeout()} bounds the wait for the response and every later read of its body.
 */
public final class AddressCheckingHttpClient extends HttpClient {

  private static final int CHUNK_SIZE = 16 * 1024;
  private static final ExecutorService WORKERS =
      Executors.newCachedThreadPool(Thread.ofPlatform().name("opaa-http-", 0).daemon().factory());

  private final CloseableHttpClient client;
  private final RequestConfig defaultRequestConfig;
  private final Duration connectTimeout;
  private final ProxySelector proxySelector;
  private final SSLContext sslContext;

  private AddressCheckingHttpClient(Builder builder) {
    this.connectTimeout = builder.connectTimeout;
    this.sslContext = builder.sslContext;
    CheckedDnsResolver dnsResolver = new CheckedDnsResolver(builder.resolver);
    HttpRoutePlanner routePlanner;
    if (builder.proxyHost != null) {
      dnsResolver.exemptProxy(builder.proxyHost);
      this.proxySelector = null;
      routePlanner =
          new DefaultProxyRoutePlanner(new HttpHost("http", builder.proxyHost, builder.proxyPort));
    } else {
      this.proxySelector = ProxySelector.getDefault();
      routePlanner = new SystemDefaultRoutePlanner(proxySelector);
    }
    ConnectionConfig.Builder connection =
        ConnectionConfig.custom().setValidateAfterInactivity(TimeValue.ofSeconds(1));
    if (connectTimeout != null) {
      connection.setConnectTimeout(Timeout.of(connectTimeout));
    }
    ClientTlsStrategyBuilder tls = ClientTlsStrategyBuilder.create();
    if (sslContext != null) {
      tls.setSslContext(sslContext);
    }
    this.client =
        HttpClients.custom()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(dnsResolver)
                    .setTlsSocketStrategy(tls.buildClassic())
                    .setDefaultConnectionConfig(connection.build())
                    .setMaxConnTotal(Integer.MAX_VALUE)
                    .setMaxConnPerRoute(Integer.MAX_VALUE)
                    .build())
            .setRoutePlanner(new ProxyExemptingRoutePlanner(routePlanner, dnsResolver))
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableContentCompression()
            .disableAutomaticRetries()
            .disableAuthCaching()
            .build();
    RequestConfig.Builder request = RequestConfig.custom();
    if (builder.responseTimeout != null) {
      request.setResponseTimeout(Timeout.of(builder.responseTimeout));
    }
    this.defaultRequestConfig = request.build();
  }

  /** A builder whose clients resolve every connection through {@code resolver}. */
  public static Builder newBuilder(ConnectionAddressResolver resolver) {
    return new Builder(resolver);
  }

  /** Builder for {@link AddressCheckingHttpClient}. */
  public static final class Builder {
    private final ConnectionAddressResolver resolver;
    private Duration connectTimeout;
    private Duration responseTimeout;
    private String proxyHost;
    private int proxyPort;
    private SSLContext sslContext;

    private Builder(ConnectionAddressResolver resolver) {
      if (resolver == null) {
        throw new IllegalArgumentException("a connection address resolver is required");
      }
      this.resolver = resolver;
    }

    public Builder connectTimeout(Duration timeout) {
      this.connectTimeout = timeout;
      return this;
    }

    /** The response timeout of a request that sets no {@link HttpRequest#timeout()} itself. */
    public Builder responseTimeout(Duration timeout) {
      this.responseTimeout = timeout;
      return this;
    }

    /** An HTTP proxy for every request; {@code null} or blank keeps the default selector. */
    public Builder proxy(String host, int port) {
      this.proxyHost = host == null || host.isBlank() ? null : host;
      this.proxyPort = port;
      return this;
    }

    public Builder sslContext(SSLContext context) {
      this.sslContext = context;
      return this;
    }

    public AddressCheckingHttpClient build() {
      return new AddressCheckingHttpClient(this);
    }
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException("interrupted before sending " + request.method());
    }
    HttpUriRequestBase outgoing = new HttpUriRequestBase(request.method(), request.uri());
    request
        .headers()
        .map()
        .forEach((name, values) -> values.forEach(v -> outgoing.addHeader(name, v)));
    byte[] body = bodyOf(request);
    if (body != null) {
      outgoing.setEntity(new ByteArrayEntity(body, null));
    }
    HttpClientContext context = HttpClientContext.create();
    context.setRequestConfig(
        request
            .timeout()
            .map(t -> RequestConfig.copy(defaultRequestConfig).setResponseTimeout(Timeout.of(t)))
            .map(RequestConfig.Builder::build)
            .orElse(defaultRequestConfig));
    CompletableFuture<ClassicHttpResponse> exchange = new CompletableFuture<>();
    WORKERS.execute(
        () -> {
          try {
            exchange.complete(client.executeOpen(null, outgoing, context));
          } catch (IOException | RuntimeException e) {
            exchange.completeExceptionally(e);
          }
        });
    ClassicHttpResponse response;
    try {
      response = exchange.get();
    } catch (InterruptedException e) {
      outgoing.cancel();
      exchange.thenAccept(AddressCheckingHttpClient::closeQuietly);
      throw e;
    } catch (ExecutionException e) {
      throw translate(e.getCause());
    }
    return respond(request, outgoing, response, handler);
  }

  private static IOException translate(Throwable failure) {
    if (failure instanceof CheckedDnsResolver.RejectedAddressException rejected) {
      return rejected.rejection();
    }
    if (failure instanceof ConnectTimeoutException) {
      HttpConnectTimeoutException timeout = new HttpConnectTimeoutException(failure.getMessage());
      timeout.initCause(failure);
      return timeout;
    }
    if (failure instanceof SocketTimeoutException) {
      HttpTimeoutException timeout = new HttpTimeoutException("request timed out");
      timeout.initCause(failure);
      return timeout;
    }
    if (failure instanceof IOException io) {
      return io;
    }
    if (failure instanceof RuntimeException runtime) {
      throw runtime;
    }
    return new IOException(failure);
  }

  private static void closeQuietly(ClassicHttpResponse response) {
    try {
      response.close();
    } catch (IOException e) {
      // the connection is discarded either way
    }
  }

  private static <T> HttpResponse<T> respond(
      HttpRequest request,
      Cancellable exchange,
      ClassicHttpResponse response,
      HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    HttpHeaders headers = headersOf(response);
    int status = response.getCode();
    HttpResponse.ResponseInfo info =
        new HttpResponse.ResponseInfo() {
          @Override
          public int statusCode() {
            return status;
          }

          @Override
          public HttpHeaders headers() {
            return headers;
          }

          @Override
          public Version version() {
            return Version.HTTP_1_1;
          }
        };
    HttpResponse.BodySubscriber<T> subscriber;
    try {
      subscriber = handler.apply(info);
    } catch (RuntimeException e) {
      exchange.cancel();
      closeQuietly(response);
      throw e;
    }
    HttpEntity entity = response.getEntity();
    InputStream content = entity == null ? InputStream.nullInputStream() : entity.getContent();
    StreamSubscription subscription =
        new StreamSubscription(content, response, exchange, subscriber);
    subscriber.onSubscribe(subscription);
    T body;
    try {
      body = subscriber.getBody().toCompletableFuture().get();
    } catch (InterruptedException e) {
      subscription.cancel();
      throw e;
    } catch (ExecutionException e) {
      subscription.cancel();
      if (e.getCause() instanceof IOException io) {
        throw io;
      }
      throw new IOException(e.getCause());
    }
    return new Response<>(request, status, headers, body);
  }

  private static HttpHeaders headersOf(ClassicHttpResponse response) {
    Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Header header : response.getHeaders()) {
      map.computeIfAbsent(header.getName(), name -> new ArrayList<>()).add(header.getValue());
    }
    return HttpHeaders.of(map, (name, value) -> true);
  }

  /** The request body as bytes, or {@code null} for a request without one. */
  private static byte[] bodyOf(HttpRequest request) throws IOException, InterruptedException {
    Optional<HttpRequest.BodyPublisher> publisher = request.bodyPublisher();
    if (publisher.isEmpty() || publisher.get().contentLength() == 0) {
      return null;
    }
    CompletableFuture<byte[]> collected = new CompletableFuture<>();
    publisher
        .get()
        .subscribe(
            new Flow.Subscriber<ByteBuffer>() {
              private final ByteArrayOutputStream out = new ByteArrayOutputStream();

              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.write(chunk, 0, chunk.length);
              }

              @Override
              public void onError(Throwable throwable) {
                collected.completeExceptionally(throwable);
              }

              @Override
              public void onComplete() {
                collected.complete(out.toByteArray());
              }
            });
    try {
      return collected.get();
    } catch (ExecutionException e) {
      throw new IOException("request body could not be read", e.getCause());
    }
  }

  /**
   * Feeds the response stream to a {@link HttpResponse.BodySubscriber} on demand, from a worker
   * thread - as the JDK client does, so a reader blocked on the body stays interruptible. At most
   * one worker reads and signals at a time. A cancellation aborts the connection rather than
   * draining it, so it is never reused; a completed body releases it for reuse.
   */
  private static final class StreamSubscription implements Flow.Subscription {
    private final InputStream in;
    private final ClassicHttpResponse response;
    private final Cancellable exchange;
    private final Flow.Subscriber<? super List<ByteBuffer>> subscriber;
    private long demand;
    private boolean pumping;
    private boolean done;

    StreamSubscription(
        InputStream in,
        ClassicHttpResponse response,
        Cancellable exchange,
        Flow.Subscriber<? super List<ByteBuffer>> subscriber) {
      this.in = in;
      this.response = response;
      this.exchange = exchange;
      this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
      if (n <= 0) {
        if (abort()) {
          subscriber.onError(new IllegalArgumentException("non-positive demand: " + n));
        }
        return;
      }
      synchronized (this) {
        demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
        if (pumping || done) {
          return;
        }
        pumping = true;
      }
      WORKERS.execute(this::pump);
    }

    private void pump() {
      while (true) {
        synchronized (this) {
          if (done) {
            pumping = false;
          } else if (demand == 0) {
            pumping = false;
            return;
          } else {
            demand--;
          }
        }
        if (!pumping) {
          closeQuietly(response);
          return;
        }
        byte[] buffer = new byte[CHUNK_SIZE];
        int read;
        try {
          read = in.read(buffer);
        } catch (IOException e) {
          if (endOfBody()) {
            subscriber.onError(e);
          }
          return;
        }
        if (read < 0) {
          if (endOfBody()) {
            subscriber.onComplete();
          }
          return;
        }
        subscriber.onNext(List.of(ByteBuffer.wrap(buffer, 0, read)));
      }
    }

    /** Ends the read on the pump thread; {@code false} if it was cancelled meanwhile. */
    private boolean endOfBody() {
      boolean signal;
      synchronized (this) {
        pumping = false;
        signal = !done;
        done = true;
      }
      closeQuietly(response);
      return signal;
    }

    @Override
    public void cancel() {
      abort();
    }

    /** Aborts the connection; {@code false} if the subscription had already ended. */
    private boolean abort() {
      boolean reading;
      synchronized (this) {
        if (done) {
          return false;
        }
        done = true;
        reading = pumping;
      }
      exchange.cancel();
      if (!reading) {
        closeQuietly(response);
      }
      return true;
    }
  }

  private record Response<T>(HttpRequest request, int statusCode, HttpHeaders headers, T body)
      implements HttpResponse<T> {

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }
  }

  /** Records the proxy of every route, so the resolver exempts it from the target check. */
  private record ProxyExemptingRoutePlanner(HttpRoutePlanner delegate, CheckedDnsResolver dns)
      implements HttpRoutePlanner {

    @Override
    public HttpRoute determineRoute(HttpHost target, HttpContext context) throws HttpException {
      HttpRoute route = delegate.determineRoute(target, context);
      if (route.getProxyHost() != null) {
        dns.exemptProxy(route.getProxyHost().getHostName());
      }
      return route;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return send(request, handler);
          } catch (IOException e) {
            throw new CompletionException(e);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
          }
        },
        WORKERS);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return sendAsync(request, handler);
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.ofNullable(connectTimeout);
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.ofNullable(proxySelector);
  }

  @Override
  public SSLContext sslContext() {
    if (sslContext != null) {
      return sslContext;
    }
    try {
      return SSLContext.getDefault();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public SSLParameters sslParameters() {
    return sslContext().getDefaultSSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  @Override
  public void shutdown() {
    close();
  }

  @Override
  public void shutdownNow() {
    close();
  }

  @Override
  public boolean awaitTermination(Duration duration) {
    return true;
  }

  @Override
  public boolean isTerminated() {
    return false;
  }

  @Override
  public void close() {
    client.close(CloseMode.GRACEFUL);
  }
}
