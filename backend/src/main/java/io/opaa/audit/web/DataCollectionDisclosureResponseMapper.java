package io.opaa.audit.web;

import io.opaa.api.dto.DataCollectionDisclosure;
import io.opaa.api.dto.DisclosureCaseCount;
import io.opaa.api.dto.DisclosureConnectorType;
import io.opaa.api.dto.DisclosureDataFlow;
import io.opaa.api.dto.DisclosureIncidentScopeCases;
import io.opaa.api.dto.DisclosureIncidentScopePurposeCases;
import io.opaa.api.dto.DisclosureInstanceState;
import io.opaa.api.dto.DisclosureModelProvider;
import io.opaa.api.dto.DisclosureProtocol;
import io.opaa.api.dto.DisclosureProtocolField;
import io.opaa.api.dto.DisclosureQueryPath;
import io.opaa.api.dto.DisclosureRunningInstance;
import io.opaa.api.dto.DisclosureSetting;
import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.compliance.FactSheetRenderer;
import io.opaa.compliance.InstanceDisclosure;
import io.opaa.compliance.InstanceDisclosure.CaseCount;
import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.Protocol;
import io.opaa.compliance.InstanceDisclosureRenderer;

/** Domain to response mapping of the disclosure of the data collection. */
final class DataCollectionDisclosureResponseMapper {

  private DataCollectionDisclosureResponseMapper() {}

  static DataCollectionDisclosure toResponse(InstanceDisclosure disclosure) {
    return new DataCollectionDisclosure()
        .opaaVersion(disclosure.opaaVersion())
        .commit(disclosure.commit())
        .generatedAt(disclosure.generatedAt())
        .notice(FactSheetRenderer.NOTICE)
        .manifest(InstanceDisclosureRenderer.manifestObject(disclosure.manifest()))
        .instance(instance(disclosure))
        .protocol(protocol(disclosure.protocol()));
  }

  private static DisclosureInstanceState instance(InstanceDisclosure disclosure) {
    return new DisclosureInstanceState()
        .settings(
            disclosure.settings().stream()
                .map(
                    value ->
                        new DisclosureSetting()
                            .key(value.key())
                            .area(value.area())
                            .source(value.source().name())
                            .defaultValue(value.defaultValue())
                            .effectiveValue(value.effectiveValue())
                            .deviates(value.deviates())
                            .changedAt(value.changedAt())
                            .range(value.range())
                            .effect(value.effect())
                            .sameOnAllInstances(value.sameOnAllInstances()))
                .toList())
        .dataFlows(
            disclosure.dataFlows().stream()
                .map(
                    flow ->
                        new DisclosureDataFlow()
                            .id(flow.id())
                            .group(flow.group().name())
                            .target(flow.target())
                            .active(flow.active()))
                .toList())
        .connectorTypes(
            disclosure.connectorTypes().stream()
                .map(
                    usage ->
                        new DisclosureConnectorType()
                            .type(usage.type())
                            .libraries(usage.libraries())
                            .connectionProfiles(usage.connectionProfiles()))
                .toList())
        .modelProviders(
            disclosure.modelProviders().stream()
                .map(
                    provider ->
                        new DisclosureModelProvider()
                            .role(provider.role())
                            .name(provider.name())
                            .baseUrl(provider.baseUrl())
                            .model(provider.model())
                            .active(provider.active()))
                .toList())
        .instances(
            disclosure.instances().stream()
                .map(
                    instance ->
                        new DisclosureRunningInstance()
                            .version(instance.version())
                            .startedAt(instance.startedAt()))
                .toList());
  }

  private static DisclosureProtocol protocol(Protocol protocol) {
    return new DisclosureProtocol()
        .fields(
            protocol.fields().stream()
                .map(
                    field ->
                        new DisclosureProtocolField()
                            .name(field.name())
                            .granularity(field.granularity())
                            .purpose(field.purpose())
                            .retention(field.retention()))
                .toList())
        .queryPaths(
            protocol.queryPaths().stream()
                .map(
                    path ->
                        new DisclosureQueryPath().name(path.name()).description(path.description()))
                .toList())
        .absentQueryPaths(protocol.absentQueryPaths())
        .networkAddress(protocol.networkAddress())
        .incidentScopeCases(incidentScopeCases(protocol.incidentScopeCases()));
  }

  private static DisclosureIncidentScopeCases incidentScopeCases(IncidentScopeCases cases) {
    return new DisclosureIncidentScopeCases()
        .from(cases.period().from())
        .to(cases.period().to())
        .smallCountThreshold(CaseCount.THRESHOLD)
        .purposes(
            cases.purposes().stream()
                .map(
                    purpose ->
                        new DisclosureIncidentScopePurposeCases()
                            .purpose(AuditIncidentScopePurpose.valueOf(purpose.purpose()))
                            .label(purpose.label())
                            .requested(caseCount(purpose.requested()))
                            .approved(caseCount(purpose.approved()))
                            .expired(caseCount(purpose.expired())))
                .toList())
        .heldChatDeletions(caseCount(cases.heldChatDeletions()))
        .notes(cases.notes());
  }

  private static DisclosureCaseCount caseCount(CaseCount count) {
    return new DisclosureCaseCount()
        .count(count.count() == null ? null : Math.toIntExact(count.count()))
        .belowThreshold(count.belowThreshold());
  }
}
