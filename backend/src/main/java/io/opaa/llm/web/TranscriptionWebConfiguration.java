package io.opaa.llm.web;

import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/** Servlet wiring of the dictation endpoint; present only with the feature speech-input. */
@Configuration
@ConditionalOnFeature(Feature.SPEECH_INPUT)
class TranscriptionWebConfiguration {

  static final String PATH = "/api/v1/transcriptions";

  /** First in the chain, so no later filter can trigger multipart parsing on this path. */
  @Bean
  FilterRegistrationBean<TranscriptionMultipartGuard> transcriptionMultipartGuard(
      ObjectMapper objectMapper, Clock clock) {
    FilterRegistrationBean<TranscriptionMultipartGuard> registration =
        new FilterRegistrationBean<>(new TranscriptionMultipartGuard(objectMapper, clock));
    registration.addUrlPatterns(PATH);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
