package io.opaa.asset;

import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetType;
import io.opaa.permission.SuccessionFinding;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalog (docs/features/spaces-and-assets.md#der-katalog): the assets a person may read by
 * {@link AssetAccessService#readableAssetIds}, united with the listed ones, over every served type
 * and within the person's organization - one query on the shell, paged and searched in SQL. Whether
 * an entry is accessible is the formula's alone; the administration's floor never counts here.
 */
@Service
@Transactional(readOnly = true)
public class AssetCatalogService {

  /** The page size bound the specification declares; a request above it is refused. */
  static final int MAX_PAGE_SIZE = 200;

  static final int MAX_QUERY_LENGTH = 200;

  private final AssetRepository assetRepository;
  private final AssetAccessService accessService;
  private final AssetTypes assetTypes;
  private final AssetOwnerNames ownerNames;
  private final AssetSuccessionSource successionSource;

  AssetCatalogService(
      AssetRepository assetRepository,
      AssetAccessService accessService,
      AssetTypes assetTypes,
      AssetOwnerNames ownerNames,
      AssetSuccessionSource successionSource) {
    this.assetRepository = assetRepository;
    this.accessService = accessService;
    this.assetTypes = assetTypes;
    this.ownerNames = ownerNames;
    this.successionSource = successionSource;
  }

  /**
   * One page of the catalog for {@code caller}, ordered by name.
   *
   * @param assetType only this type, or every served type when {@code null}.
   * @param query part of the name or the description, matched literally and case-insensitively;
   *     blank matches everything.
   */
  public AssetCatalogPage list(
      CurrentUser caller, AssetType assetType, String query, int page, int size) {
    if (page < 0) {
      throw new ValidationException("page darf nicht negativ sein");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException("size muss zwischen 1 und " + MAX_PAGE_SIZE + " liegen");
    }
    if (query != null && query.length() > MAX_QUERY_LENGTH) {
      throw new ValidationException(
          "Der Suchtext darf höchstens " + MAX_QUERY_LENGTH + " Zeichen lang sein");
    }
    List<AssetType> types = assetType == null ? assetTypes.registered() : List.of(assetType);
    if (assetType != null && assetTypes.find(assetType).isEmpty()) {
      throw new ValidationException("Unbekannter Asset-Typ: " + assetType);
    }

    Set<UUID> readable = new HashSet<>();
    for (AssetType type : types) {
      readable.addAll(accessService.readableAssetIds(type, caller.id(), caller.organizationId()));
    }
    Page<AssetCatalogRow> rows =
        assetRepository.findCatalogPage(
            caller.organizationId(),
            types,
            readable,
            likePattern(query),
            PageRequest.of(page, size));

    Map<UUID, String> names = ownerNames.of(rows.getContent());
    Map<UUID, SuccessionFinding> succession =
        successionSource.findingsAmong(rows.getContent(), false);
    List<AssetCatalogEntry> entries =
        rows.getContent().stream()
            .map(
                row ->
                    new AssetCatalogEntry(
                        row,
                        readable.contains(row.getId()),
                        names.get(row.getOwnerId()),
                        succession.get(row.getId())))
            .toList();
    return new AssetCatalogPage(entries, page, size, rows.getTotalElements(), rows.getTotalPages());
  }

  /** A {@code LIKE} pattern that takes every character of {@code query} literally. */
  static String likePattern(String query) {
    if (query == null || query.isBlank()) {
      return "%";
    }
    String escaped = query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
