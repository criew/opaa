package io.opaa.indexing.source.s3.events;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the objects an S3 event notification names, recognising the shape by its structure, not by
 * its sender (ADR-0027, Entscheidung 6): the S3 {@code Records} format (AWS via SNS/SQS forwarding,
 * Ceph RGW, MinIO - whose {@code EventName}/{@code Key} envelope is ignored in favour of its {@code
 * Records}) with the key URL-decoded ({@code application/x-www-form-urlencoded}, a {@code +} is a
 * space), the EventBridge envelope ({@code detail-type}, {@code detail.bucket.name}, {@code
 * detail.object.key} raw) and the {@code s3:TestEvent} AWS sends on set-up. A body that names no
 * object is not an error.
 */
public final class S3EventPayload {

  /** What a body carries: the objects it names and whether it was the set-up test message. */
  public record Parsed(List<S3ObjectEvent> events, boolean testEvent) {
    public Parsed {
      events = List.copyOf(events);
    }
  }

  private static final Parsed NOTHING = new Parsed(List.of(), false);

  private S3EventPayload() {}

  public static Parsed parse(byte[] body, JsonMapper mapper) {
    if (body == null || body.length == 0) {
      return NOTHING;
    }
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (JacksonException e) {
      return NOTHING;
    }
    if (root == null || !root.isObject()) {
      return NOTHING;
    }
    if ("s3:TestEvent".equals(root.path("Event").asString(null))) {
      return new Parsed(List.of(), true);
    }
    List<S3ObjectEvent> events = new ArrayList<>();
    JsonNode records = root.path("Records");
    if (records.isArray()) {
      for (JsonNode record : records) {
        addRecord(events, record);
      }
    }
    if (root.has("detail-type") && root.path("detail").isObject()) {
      addEventBridge(events, root);
    }
    return new Parsed(Collections.unmodifiableList(events), false);
  }

  private static void addRecord(List<S3ObjectEvent> events, JsonNode record) {
    JsonNode s3 = record.path("s3");
    String bucket = s3.path("bucket").path("name").asString(null);
    String rawKey = s3.path("object").path("key").asString(null);
    if (bucket == null || bucket.isBlank() || rawKey == null || rawKey.isBlank()) {
      return;
    }
    String key;
    try {
      key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      key = rawKey;
    }
    events.add(new S3ObjectEvent(bucket, key, kindOf(record.path("eventName").asString(""))));
  }

  private static void addEventBridge(List<S3ObjectEvent> events, JsonNode root) {
    JsonNode detail = root.path("detail");
    String bucket = detail.path("bucket").path("name").asString(null);
    String key = detail.path("object").path("key").asString(null);
    if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
      return;
    }
    String type = root.path("detail-type").asString("").toLowerCase(Locale.ROOT);
    S3ObjectEvent.Kind kind =
        type.contains("deleted")
            ? S3ObjectEvent.Kind.REMOVED
            : type.contains("created") ? S3ObjectEvent.Kind.CREATED : S3ObjectEvent.Kind.OTHER;
    events.add(new S3ObjectEvent(bucket, key, kind));
  }

  /** {@code ObjectCreated:*} (with or without the {@code s3:} prefix), {@code ObjectRemoved:*}. */
  static S3ObjectEvent.Kind kindOf(String eventName) {
    String name = eventName.toLowerCase(Locale.ROOT);
    if (name.startsWith("s3:")) {
      name = name.substring(3);
    }
    if (name.startsWith("objectcreated")) {
      return S3ObjectEvent.Kind.CREATED;
    }
    if (name.startsWith("objectremoved") || name.contains("deletemarkercreated")) {
      return S3ObjectEvent.Kind.REMOVED;
    }
    return S3ObjectEvent.Kind.OTHER;
  }
}
