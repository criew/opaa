package io.opaa.audit.web;

import io.opaa.api.dto.AuditChainDeviation;
import io.opaa.api.dto.AuditChainDeviationKind;
import io.opaa.api.dto.AuditChainStatus;
import io.opaa.api.dto.AuditChainVerification;
import io.opaa.audit.AuditChainReport;

/** Maps the result of a chain verification to its generated response DTO (ADR-0006). */
final class AuditChainResponseMapper {

  private AuditChainResponseMapper() {}

  static AuditChainVerification toResponse(AuditChainReport report) {
    AuditChainVerification response =
        new AuditChainVerification(
                report.month().toString(),
                AuditChainStatus.valueOf(report.status().name()),
                report.sealed(),
                report.chainedEntries(),
                report.unchainedEntries(),
                report.checkpoints(),
                report.algorithm())
            .headHash(report.headHash())
            .sealEventId(report.sealEventId());
    if (report.deviation() != null) {
      AuditChainReport.Deviation deviation = report.deviation();
      response.deviation(
          new AuditChainDeviation(
              deviation.position(),
              AuditChainDeviationKind.valueOf(deviation.kind().name()),
              deviation.segmentFrom(),
              deviation.segmentTo()));
    }
    return response;
  }
}
