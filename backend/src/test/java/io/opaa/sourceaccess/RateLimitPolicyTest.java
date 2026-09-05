package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class RateLimitPolicyTest {

  private static HttpResponse<Void> responseWithRetryAfter(String value) {
    Map<String, List<String>> headers =
        value == null ? Map.of() : Map.of("Retry-After", List.of(value));
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 429;
      }

      @Override
      public HttpRequest request() {
        return null;
      }

      @Override
      public Optional<HttpResponse<Void>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(headers, (a, b) -> true);
      }

      @Override
      public Void body() {
        return null;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }

      @Override
      public URI uri() {
        return URI.create("https://quelle.example/");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
      }
    };
  }

  @Test
  void parsesRetryAfterAsSecondsOrHttpDate() {
    assertThat(RateLimitPolicy.parseRetryAfter("7")).isEqualTo(Duration.ofSeconds(7));
    assertThat(RateLimitPolicy.parseRetryAfter(" 12 ")).isEqualTo(Duration.ofSeconds(12));

    String inTenSeconds =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.now().plusSeconds(10).atOffset(ZoneOffset.UTC));
    Duration parsed = RateLimitPolicy.parseRetryAfter(inTenSeconds);
    assertThat(parsed).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(11));

    assertThat(RateLimitPolicy.parseRetryAfter("bald")).isNull();
  }

  @Test
  void waitForDefaultsWithoutAUsableHeaderAndCapsAtMaxWait() {
    RateLimitPolicy policy = new RateLimitPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(30));

    assertThat(policy.waitFor(responseWithRetryAfter(null))).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.waitFor(responseWithRetryAfter("bald"))).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.waitFor(responseWithRetryAfter("0"))).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.waitFor(responseWithRetryAfter("-3"))).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.waitFor(responseWithRetryAfter("7"))).isEqualTo(Duration.ofSeconds(7));
    assertThat(policy.waitFor(responseWithRetryAfter("600"))).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void noneRetriesNothingAndANegativeRetryCountIsRejected() {
    assertThat(RateLimitPolicy.NONE.retries()).isFalse();
    assertThat(RateLimitPolicy.of(6, Duration.ofMinutes(2)).retries()).isTrue();
    assertThat(RateLimitPolicy.of(6, Duration.ofMinutes(2)).defaultWait())
        .isEqualTo(RateLimitPolicy.DEFAULT_WAIT);
    assertThatThrownBy(() -> new RateLimitPolicy(-1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
