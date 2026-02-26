package io.opaa.api;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Forces HTTP/1.1 for all RestClient connections when enabled. Required for OpenAI-compatible
 * servers like vLLM that do not support HTTP/2 (Uvicorn/ASGI limitation).
 *
 * <p>Enable via: {@code opaa.http.force-http1=true}
 *
 * @see <a href="https://github.com/spring-projects/spring-ai/issues/2042">Spring AI #2042</a>
 */
@Configuration
@ConditionalOnProperty(name = "opaa.http.force-http1", havingValue = "true")
public class HttpClientConfig {

  @Bean
  public RestClientCustomizer http1RestClientCustomizer() {
    return restClientBuilder ->
        restClientBuilder.requestFactory(
            new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()));
  }
}
