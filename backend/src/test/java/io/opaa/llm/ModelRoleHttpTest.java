package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class ModelRoleHttpTest {

  private static IllegalStateException failure(String message, Throwable cause) {
    return new IllegalStateException(message, cause);
  }

  @Test
  void statusTextsAreSharedRoleSpecificOrFallback() {
    Map<Integer, String> roleSpecific = Map.of(413, "HTTP 413: too large");

    assertThat(ModelRoleHttp.describeStatus(401, "x", roleSpecific))
        .isEqualTo("HTTP 401: authentication failed, check the access key");
    assertThat(ModelRoleHttp.describeStatus(403, "x", roleSpecific))
        .isEqualTo("HTTP 403: the endpoint refused access");
    assertThat(ModelRoleHttp.describeStatus(404, "x", roleSpecific))
        .isEqualTo("HTTP 404: unknown path or model identifier");
    assertThat(ModelRoleHttp.describeStatus(413, "x", roleSpecific))
        .isEqualTo("HTTP 413: too large");
    assertThat(ModelRoleHttp.describeStatus(502, "rerank", roleSpecific))
        .isEqualTo("HTTP 502 from the rerank endpoint");
  }

  @Test
  void connectionErrorsAreClassifiedMostSpecificFirst() {
    assertThat(describe(new UnknownHostException("h")))
        .isEqualTo("host not found (DNS resolution failed)");
    assertThat(describe(new ConnectException("c"))).isEqualTo("connection refused");
    assertThat(describe(new HttpConnectTimeoutException("c"))).isEqualTo("role connect text");
    assertThat(describe(new HttpTimeoutException("t"))).isEqualTo("request timed out");
    // SocketTimeoutException is an InterruptedIOException and must keep the timeout text.
    assertThat(describe(new SocketTimeoutException("t"))).isEqualTo("request timed out");
    assertThat(describe(new SSLHandshakeException("s"))).isEqualTo("TLS handshake failed");
    assertThat(describe(new InterruptedIOException("i"))).isEqualTo("request interrupted");
    assertThat(describe(new IOException("x"))).isEqualTo("endpoint not reachable");
  }

  private static String describe(IOException e) {
    return ModelRoleHttp.describeConnectionError(e, "role connect text");
  }

  @Test
  void endpointUriAppendsThePath() {
    assertThat(ModelRoleHttp.endpointUri("https://host/v1/", "/models", ModelRoleHttpTest::failure))
        .hasToString("https://host/v1/models");
  }

  @Test
  void endpointUriHandsTheCauseToTheRoleOnlyWhereThereIsOne() {
    assertThatThrownBy(
            () ->
                ModelRoleHttp.endpointUri(
                    "https://user:secret@host", "/models", ModelRoleHttpTest::failure))
        .hasMessage("base URL carries credentials")
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ModelRoleHttp.endpointUri("http://host/a b", "/models", ModelRoleHttpTest::failure))
        .hasMessage("base URL is not a valid URI")
        .hasCauseInstanceOf(URISyntaxException.class);
    assertThatThrownBy(
            () -> ModelRoleHttp.endpointUri("ftp://host", "/models", ModelRoleHttpTest::failure))
        .hasMessage("base URL must start with http:// or https://")
        .hasNoCause();
  }
}
