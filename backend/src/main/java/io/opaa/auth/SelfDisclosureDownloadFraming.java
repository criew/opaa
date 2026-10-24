package io.opaa.auth;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The frame headers of both API chains: every answer refuses framing ({@code DENY}) except the
 * self-disclosure download, which the application submits into a hidden frame of its own and which
 * may therefore be framed by the same origin ({@code SAMEORIGIN}, {@code frame-ancestors 'self'}).
 * Browsers cancel a download in a frame whose answer refuses framing.
 */
public final class SelfDisclosureDownloadFraming {

  static final RequestMatcher DOWNLOAD =
      PathPatternRequestMatcher.withDefaults()
          .matcher(HttpMethod.POST, "/api/v1/me/self-disclosure/download");

  private SelfDisclosureDownloadFraming() {}

  public static Customizer<HeadersConfigurer<HttpSecurity>> frameHeaders() {
    return headers ->
        headers
            .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
            .addHeaderWriter(
                new DelegatingRequestMatcherHeaderWriter(
                    new NegatedRequestMatcher(DOWNLOAD),
                    new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY)))
            .addHeaderWriter(
                new DelegatingRequestMatcherHeaderWriter(
                    DOWNLOAD, new XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN)))
            .addHeaderWriter(
                new DelegatingRequestMatcherHeaderWriter(
                    DOWNLOAD,
                    new StaticHeadersWriter("Content-Security-Policy", "frame-ancestors 'self'")));
  }
}
