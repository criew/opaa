package io.opaa.group.sync;

import tools.jackson.databind.json.JsonMapper;

/**
 * The persisted form of a {@link SyncReport} in {@code directory_sync_pending_plans.report}: the
 * report exactly as the run presented it, so the management can show it again without reading the
 * directory a second time (#1816).
 *
 * <p>Read back by this class alone - never by another system - so round-trip fidelity is the whole
 * contract and the mapper's own defaults for records and {@code Instant} suffice. {@code
 * DirectorySyncReportCodecTest} holds that round trip.
 */
final class DirectorySyncReportCodec {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private DirectorySyncReportCodec() {}

  static String write(SyncReport report) {
    return MAPPER.writeValueAsString(report);
  }

  static SyncReport read(String json) {
    return MAPPER.readValue(json, SyncReport.class);
  }
}
