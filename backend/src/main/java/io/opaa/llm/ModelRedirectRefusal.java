package io.opaa.llm;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.connection.RealCall;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * Keeps the OkHttp client Spring AI builds for the chat and embedding roles from following
 * redirects, which its builder cannot switch off. The last application interceptor sends the
 * request itself on a copy of the call's own client (timeouts, proxy selector, connection pool)
 * with redirects off; a 3xx answer comes back as that status with a fixed error text instead of the
 * server's body and headers, so neither its content nor its {@code Location} goes further.
 *
 * <p>Built for synchronous callers: a cancel of the outer call does not reach the inner one, which
 * ends by its own copy of the timeouts or when its body is closed; a close that fails while the
 * body is still being read cancels the inner call, so an abandoned stream does not keep the model
 * writing. A refusal is logged at WARN once per endpoint and instance, then at DEBUG (ADR-0053, je
 * Instanz).
 */
public final class ModelRedirectRefusal implements OpenAiHttpClientBuilderCustomizer {

  public static final ModelRedirectRefusal INSTANCE = new ModelRedirectRefusal();

  private static final Logger log = LoggerFactory.getLogger(ModelRedirectRefusal.class);

  private static final MediaType JSON = MediaType.get("application/json");

  /** The SDK reads {@code error.message} into its exception message. */
  private static final String REFUSAL_BODY =
      "{\"error\":{\"message\":\"the model endpoint answered with a redirect, which is not"
          + " followed\"}}";

  /** Bounds the endpoints remembered for the WARN-once rule; beyond it every refusal is DEBUG. */
  private static final int MAX_WARNED_ENDPOINTS = 256;

  private static final Set<String> warnedEndpoints = ConcurrentHashMap.newKeySet();

  private ModelRedirectRefusal() {}

  @Override
  public void customize(SpringAiOpenAiHttpClient.Builder builder) {
    builder.interceptor(ModelRedirectRefusal::intercept);
  }

  /**
   * Runs the request with redirects off. Never calls {@code chain.proceed}: the chain below an
   * application interceptor follows redirects before any later hook could see them.
   */
  static Response intercept(Interceptor.Chain chain) throws IOException {
    if (!(chain.call() instanceof RealCall call)) {
      throw new IOException("Cannot switch off redirects for this model client call");
    }
    OkHttpClient.Builder withoutRedirects =
        call.getClient().newBuilder().followRedirects(false).followSslRedirects(false);
    // Every application interceptor before this one has already shaped chain.request().
    withoutRedirects.interceptors().clear();
    Call inner = withoutRedirects.build().newCall(chain.request());
    Response response = inner.execute();
    if (!response.isRedirect()) {
      return response.newBuilder().body(new CancelOnClose(response.body(), inner)).build();
    }
    response.close();
    logRefusal(
        ModelEndpointUri.withoutSecrets(chain.request().url().newBuilder().query(null).toString()),
        response.code());
    return new Response.Builder()
        .request(response.request())
        .protocol(response.protocol())
        .code(response.code())
        .message("Redirect not followed")
        .header("Content-Type", JSON.toString())
        .body(ResponseBody.create(REFUSAL_BODY, JSON))
        .build();
  }

  private static void logRefusal(String endpoint, int status) {
    String message =
        "Model endpoint {} answered HTTP {} with a redirect; redirects are not followed";
    boolean first = warnedEndpoints.size() < MAX_WARNED_ENDPOINTS && warnedEndpoints.add(endpoint);
    if (first) {
      log.warn(message, endpoint, status);
    } else {
      log.debug(message, endpoint, status);
    }
  }

  /**
   * The inner call's body; a close that OkHttp cannot complete cancels the inner call. OkHttp's own
   * close fails while another thread is still reading the body - a streamed answer abandoned
   * mid-read - and would leave the connection to the model open; the cancel closes it. Any other
   * close stays OkHttp's, so a connection whose answer ended goes back to the pool.
   */
  private static final class CancelOnClose extends ResponseBody {

    private final ResponseBody delegate;
    private final Call call;

    CancelOnClose(ResponseBody delegate, Call call) {
      this.delegate = delegate;
      this.call = call;
    }

    @Override
    public MediaType contentType() {
      return delegate.contentType();
    }

    @Override
    public long contentLength() {
      return delegate.contentLength();
    }

    @Override
    public BufferedSource source() {
      return delegate.source();
    }

    @Override
    public void close() {
      try {
        delegate.close();
      } catch (RuntimeException e) {
        call.cancel();
        throw e;
      }
    }
  }
}
