package io.opaa.template;

import io.opaa.asset.AssetResponsibilityService;
import io.opaa.permission.AssetAccessService;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RunTemplates} as delivered: every template the asker may read by the formula of the asset
 * shell - the template list's rule, without the administration's floor - while the switch
 * "Vorlagen" is on, minus every locked template. The lock is read from the database on every call,
 * so it holds on every instance from the next call.
 */
@Service
@Transactional(readOnly = true)
class ReadableRunTemplates implements RunTemplates {

  private final DocumentTemplateSettingsService settings;
  private final AssetAccessService accessService;
  private final DocumentTemplateRepository repository;
  private final DocumentTemplateFiles files;
  private final AssetResponsibilityService responsibilities;

  ReadableRunTemplates(
      DocumentTemplateSettingsService settings,
      AssetAccessService accessService,
      DocumentTemplateRepository repository,
      DocumentTemplateFiles files,
      AssetResponsibilityService responsibilities) {
    this.settings = settings;
    this.accessService = accessService;
    this.repository = repository;
    this.files = files;
    this.responsibilities = responsibilities;
  }

  @Override
  public List<RunTemplate> available(UUID organizationId, UUID userId) {
    if (!settings.isEnabled()) {
      return List.of();
    }
    Set<UUID> readable =
        accessService.readableAssetIds(DocumentTemplate.ASSET_TYPE, userId, organizationId);
    Set<UUID> locked = responsibilities.lockedAmong(readable);
    return repository.findAllById(readable).stream()
        .filter(template -> organizationId.equals(template.getOrganizationId()))
        .filter(template -> !locked.contains(template.getId()))
        .sorted(
            Comparator.comparing(DocumentTemplate::getName).thenComparing(DocumentTemplate::getId))
        .map(ReadableRunTemplates::runTemplate)
        .toList();
  }

  @Override
  public Optional<TemplateToFill> forFilling(UUID templateId, UUID organizationId, UUID userId) {
    if (templateId == null || !settings.isEnabled()) {
      return Optional.empty();
    }
    Set<UUID> readable =
        accessService.readableAssetIds(DocumentTemplate.ASSET_TYPE, userId, organizationId);
    if (!readable.contains(templateId) || responsibilities.isLocked(templateId)) {
      return Optional.empty();
    }
    return repository
        .findById(templateId)
        .filter(template -> organizationId.equals(template.getOrganizationId()))
        .flatMap(
            template ->
                files
                    .load(template.getId())
                    .map(
                        content ->
                            new TemplateToFill(
                                runTemplate(template), template.getFileName(), content)));
  }

  private static RunTemplate runTemplate(DocumentTemplate template) {
    return new RunTemplate(
        template.getId(),
        template.getName(),
        template.getDescription(),
        template.getPlaceholders());
  }
}
