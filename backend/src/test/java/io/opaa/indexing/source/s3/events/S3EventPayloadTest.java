package io.opaa.indexing.source.s3.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The three shapes an S3 event notification arrives in, recognised by structure (ADR-0027). */
class S3EventPayloadTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  private S3EventPayload.Parsed parse(String json) {
    return S3EventPayload.parse(json.getBytes(StandardCharsets.UTF_8), mapper);
  }

  @Test
  void readsTheRecordsFormatWithUrlDecodedKeysAndEventKinds() {
    S3EventPayload.Parsed parsed =
        parse(
            """
            {"EventName":"s3:ObjectCreated:Put","Key":"dokumente/2025/protokoll.pdf",
             "Records":[
               {"eventName":"s3:ObjectCreated:Put","s3":{"bucket":{"name":"dokumente"},
                 "object":{"key":"2025/Sitzung+1/protokoll%20final.pdf"}}},
               {"eventName":"ObjectRemoved:Delete","s3":{"bucket":{"name":"dokumente"},
                 "object":{"key":"2025/alt.pdf"}}},
               {"eventName":"ObjectRemoved:DeleteMarkerCreated","s3":{"bucket":{"name":"archiv"},
                 "object":{"key":"x.pdf"}}},
               {"eventName":"s3:ObjectRestore:Completed","s3":{"bucket":{"name":"archiv"},
                 "object":{"key":"y.pdf"}}},
               {"eventName":"s3:ObjectCreated:Put","s3":{"bucket":{"name":""},"object":{"key":"z"}}}
             ]}
            """);

    assertThat(parsed.testEvent()).isFalse();
    assertThat(parsed.events())
        .containsExactly(
            new S3ObjectEvent(
                "dokumente", "2025/Sitzung 1/protokoll final.pdf", S3ObjectEvent.Kind.CREATED),
            new S3ObjectEvent("dokumente", "2025/alt.pdf", S3ObjectEvent.Kind.REMOVED),
            new S3ObjectEvent("archiv", "x.pdf", S3ObjectEvent.Kind.REMOVED),
            new S3ObjectEvent("archiv", "y.pdf", S3ObjectEvent.Kind.OTHER));
  }

  @Test
  void readsTheEventBridgeEnvelopeWithRawKeys() {
    S3EventPayload.Parsed created =
        parse(
            """
            {"detail-type":"Object Created","source":"aws.s3",
             "detail":{"bucket":{"name":"dokumente"},"object":{"key":"2025/a+b c.pdf","size":5}}}
            """);
    S3EventPayload.Parsed deleted =
        parse(
            """
            {"detail-type":"Object Deleted","detail":{"bucket":{"name":"dokumente"},
             "object":{"key":"2025/a.pdf"},"deletion-type":"Delete Marker Created"}}
            """);

    assertThat(created.events())
        .containsExactly(
            new S3ObjectEvent("dokumente", "2025/a+b c.pdf", S3ObjectEvent.Kind.CREATED));
    assertThat(deleted.events())
        .containsExactly(new S3ObjectEvent("dokumente", "2025/a.pdf", S3ObjectEvent.Kind.REMOVED));
  }

  @Test
  void theSetUpTestEventIsRecognisedAndAnythingElseNamesNothing() {
    assertThat(parse("{\"Service\":\"Amazon S3\",\"Event\":\"s3:TestEvent\"}").testEvent())
        .isTrue();
    assertThat(parse("{\"Records\":\"nicht\"}").events()).isEmpty();
    assertThat(parse("[1,2]").events()).isEmpty();
    assertThat(parse("kein json").events()).isEmpty();
    assertThat(S3EventPayload.parse(null, mapper).events()).isEmpty();
    assertThat(S3EventPayload.kindOf("s3:ObjectCreated:CompleteMultipartUpload"))
        .isEqualTo(S3ObjectEvent.Kind.CREATED);
    assertThat(List.of(S3EventPayload.kindOf("ObjectTagging:Put")))
        .containsExactly(S3ObjectEvent.Kind.OTHER);
  }
}
