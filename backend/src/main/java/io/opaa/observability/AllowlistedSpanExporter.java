package io.opaa.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Hands a span to the collector with only the attributes of {@link #ALLOWED}: route templates,
 * methods, status and outcome classes, model and token counts - never a URL with path or query, a
 * principal, an SQL text, a prompt, an answer or a search text, whichever instrumentation recorded
 * it. Events (an exception message among them), links to other spans with their attributes and the
 * status description are dropped as well.
 */
public final class AllowlistedSpanExporter implements SpanExporter {

  /** The attribute keys a span keeps; every other key is dropped before the export. */
  static final Set<String> ALLOWED =
      Set.of(
          // Spring MVC and HTTP clients: the route template, never http.url
          "method",
          "uri",
          "status",
          "outcome",
          "exception",
          "client.name",
          "http.request.method",
          "http.response.status_code",
          "http.route",
          "error.type",
          // Spring AI: operation, provider, model, finish reason, token counts
          "gen_ai.operation.name",
          "gen_ai.system",
          "gen_ai.provider.name",
          "gen_ai.request.model",
          "gen_ai.response.model",
          "gen_ai.response.finish_reasons",
          "gen_ai.usage.input_tokens",
          "gen_ai.usage.output_tokens",
          "gen_ai.usage.total_tokens",
          "spring.ai.kind",
          "db.system",
          "db.operation.name",
          // scheduled tasks
          "code.function",
          "code.namespace");

  private final SpanExporter delegate;

  public AllowlistedSpanExporter(SpanExporter delegate) {
    this.delegate = delegate;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    return delegate.export(spans.stream().map(AllowlistedSpanExporter::allowlisted).toList());
  }

  @Override
  public CompletableResultCode flush() {
    return delegate.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return delegate.shutdown();
  }

  static SpanData allowlisted(SpanData span) {
    AttributesBuilder kept = Attributes.builder();
    span.getAttributes()
        .forEach(
            (key, value) -> {
              if (ALLOWED.contains(key.getKey())) {
                @SuppressWarnings("unchecked")
                AttributeKey<Object> typed = (AttributeKey<Object>) key;
                kept.put(typed, value);
              }
            });
    Attributes attributes = kept.build();
    StatusData status =
        span.getStatus().getStatusCode() == StatusCode.ERROR
            ? StatusData.error()
            : span.getStatus().getStatusCode() == StatusCode.OK
                ? StatusData.ok()
                : StatusData.unset();
    return new DelegatingSpanData(span) {
      @Override
      public Attributes getAttributes() {
        return attributes;
      }

      @Override
      public int getTotalAttributeCount() {
        return attributes.size();
      }

      @Override
      public List<EventData> getEvents() {
        return List.of();
      }

      @Override
      public int getTotalRecordedEvents() {
        return 0;
      }

      @Override
      public StatusData getStatus() {
        return status;
      }

      @Override
      public List<LinkData> getLinks() {
        return List.of();
      }

      @Override
      public int getTotalRecordedLinks() {
        return 0;
      }
    };
  }
}
