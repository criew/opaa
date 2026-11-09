package io.opaa.llm.web;

import io.opaa.auth.CorsProperties;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.CurrentUserArgumentResolver;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.llm.PersonalTranscriptionVocabularyService;
import io.opaa.llm.RealtimeTranscriptionRole;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * The live dictation endpoint {@code WS /api/v1/transcriptions/stream} (ADR-0072), present only
 * with the feature speech-input. The upgrade is an ordinary request under {@code /api/**}: the
 * security chain authenticates it like every REST call, with the token the browser hands over as
 * subprotocol ({@link TranscriptionStreamCredentialsFilter}). The handshake answers only the
 * browser's own origin or one of {@code opaa.cors.allowed-origins}, so another site cannot open a
 * session in the name of a signed-in person.
 */
@Configuration
@EnableWebSocket
@ConditionalOnFeature(Feature.SPEECH_INPUT)
class TranscriptionStreamConfiguration implements WebSocketConfigurer {

  static final String PATH = "/api/v1/transcriptions/stream";

  private final TranscriptionStreamHandler handler;
  private final String[] allowedOrigins;
  private final ScheduledExecutorService timers;
  private final ExecutorService connector;

  TranscriptionStreamConfiguration(
      RealtimeTranscriptionRole realtime,
      PersonalTranscriptionVocabularyService vocabularies,
      ObjectMapper json,
      Clock clock,
      CorsProperties cors) {
    this.timers =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("transcription-stream-timer").factory());
    this.connector = Executors.newVirtualThreadPerTaskExecutor();
    this.handler =
        new TranscriptionStreamHandler(realtime, vocabularies, json, clock, timers, connector);
    this.allowedOrigins = cors.allowedOrigins().toArray(String[]::new);
  }

  @PreDestroy
  void shutdown() {
    timers.shutdownNow();
    connector.shutdownNow();
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    DefaultHandshakeHandler handshake = new DefaultHandshakeHandler();
    handshake.setSupportedProtocols(TranscriptionStreamCredentialsFilter.PROTOCOL);
    registry
        .addHandler(handler, PATH)
        .setHandshakeHandler(handshake)
        .addInterceptors(new CallerInterceptor())
        .setAllowedOrigins(allowedOrigins);
  }

  /** Runs before the security chain, so the token reaches it as an ordinary header. */
  @Bean
  FilterRegistrationBean<TranscriptionStreamCredentialsFilter> transcriptionStreamCredentials() {
    FilterRegistrationBean<TranscriptionStreamCredentialsFilter> registration =
        new FilterRegistrationBean<>(new TranscriptionStreamCredentialsFilter());
    registration.addUrlPatterns(PATH);
    registration.setOrder(TranscriptionStreamCredentialsFilter.ORDER);
    return registration;
  }

  /** Takes the caller the security chain resolved; refuses a call through an access token. */
  static final class CallerInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes) {
      CurrentUser caller =
          request instanceof ServletServerHttpRequest servlet
              ? CurrentUserArgumentResolver.callerOf(servlet.getServletRequest())
              : null;
      if (caller == null || caller.accessTokenId() != null) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
      }
      attributes.put(TranscriptionStreamHandler.CALLER_ATTRIBUTE, caller);
      return true;
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception) {}
  }
}
