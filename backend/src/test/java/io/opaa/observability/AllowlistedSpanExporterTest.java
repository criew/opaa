package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What leaves with a span besides its attributes: no link and nothing a link carries. */
class AllowlistedSpanExporterTest {

  @Test
  void linksToOtherSpansAndTheirAttributesAreDropped() {
    SpanData span = mock(SpanData.class);
    when(span.getAttributes())
        .thenReturn(Attributes.of(AttributeKey.stringKey("uri"), "/api/v1/spaces"));
    when(span.getStatus()).thenReturn(StatusData.unset());
    when(span.getLinks())
        .thenReturn(
            List.of(
                LinkData.create(
                    SpanContext.create(
                        "0af7651916cd43dd8448eb211c80319c",
                        "b7ad6b7169203331",
                        TraceFlags.getSampled(),
                        TraceState.getDefault()),
                    Attributes.of(AttributeKey.stringKey("user"), "Mustermann"))));
    when(span.getTotalRecordedLinks()).thenReturn(1);

    SpanData exported = AllowlistedSpanExporter.allowlisted(span);

    assertThat(exported.getLinks()).isEmpty();
    assertThat(exported.getTotalRecordedLinks()).isZero();
    assertThat(exported.getAttributes().asMap()).containsOnlyKeys(AttributeKey.stringKey("uri"));
  }
}
