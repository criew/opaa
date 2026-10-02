package io.opaa.asset;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.CatalogEntryStatus;
import io.opaa.api.types.CatalogVisibility;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetType;
import io.opaa.permission.ReadableAssets;
import io.opaa.permission.SuccessionFinding;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalog (docs/features/spaces-and-assets.md#der-katalog): exactly the assets a person may
 * read by {@link AssetAccessService#readableAssets}, over every served type and within the person's
 * organization - one query on the shell, paged, searched and sorted in SQL. Every filter narrows
 * the readable set before the query, so no entry and no count ever includes an unreadable asset;
 * the administration's floor never counts here. What a page shows beyond the shell comes in grouped
 * queries per type present on it. The caller's own favorites come first in every order; no other
 * person's mark is read.
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
  private final Map<AssetType, AssetExtent> extents;
  private final Map<AssetType, AssetCatalogFactSource> factSources;
  private final AssetFavoriteRepository favorites;

  AssetCatalogService(
      AssetRepository assetRepository,
      AssetAccessService accessService,
      AssetTypes assetTypes,
      AssetOwnerNames ownerNames,
      AssetSuccessionSource successionSource,
      List<AssetExtent> extents,
      List<AssetCatalogFactSource> factSources,
      AssetFavoriteRepository favorites) {
    this.assetRepository = assetRepository;
    this.accessService = accessService;
    this.assetTypes = assetTypes;
    this.ownerNames = ownerNames;
    this.successionSource = successionSource;
    this.extents =
        extents.stream().collect(Collectors.toMap(AssetExtent::assetType, extent -> extent));
    this.favorites = favorites;
    this.factSources =
        factSources.stream()
            .collect(Collectors.toMap(AssetCatalogFactSource::assetType, source -> source));
  }

  /** One page of every readable asset of {@code assetType} matching {@code text}, by name. */
  public AssetCatalogPage list(
      CurrentUser caller, AssetType assetType, String text, int page, int size) {
    return list(caller, AssetCatalogQuery.of(assetType, text), page, size);
  }

  /** One page of the catalog for {@code caller}. */
  public AssetCatalogPage list(CurrentUser caller, AssetCatalogQuery query, int page, int size) {
    if (page < 0) {
      throw new ValidationException("page darf nicht negativ sein");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ValidationException("size muss zwischen 1 und " + MAX_PAGE_SIZE + " liegen");
    }
    if (query.text() != null && query.text().length() > MAX_QUERY_LENGTH) {
      throw new ValidationException(
          "Der Suchtext darf höchstens " + MAX_QUERY_LENGTH + " Zeichen lang sein");
    }
    AssetType assetType = query.assetType();
    List<AssetType> types = assetType == null ? assetTypes.registered() : List.of(assetType);
    if (assetType != null && assetTypes.find(assetType).isEmpty()) {
      throw new ValidationException("Unbekannter Asset-Typ: " + assetType);
    }

    Selection selection = select(caller, types, query);
    if (selection.ids().isEmpty()) {
      return new AssetCatalogPage(List.of(), page, size, 0, 0);
    }
    PageRequest pageRequest = PageRequest.of(page, size);
    String pattern = likePattern(query.text());
    Page<AssetCatalogRow> rows =
        switch (query.sort()) {
          case NAME ->
              assetRepository.findCatalogPage(
                  caller.organizationId(),
                  types,
                  selection.ids(),
                  pattern,
                  caller.id(),
                  query.favoritesOnly(),
                  pageRequest);
          case UPDATED_AT ->
              assetRepository.findCatalogPageByUpdatedAt(
                  caller.organizationId(),
                  types,
                  selection.ids(),
                  pattern,
                  caller.id(),
                  query.favoritesOnly(),
                  pageRequest);
        };

    List<AssetCatalogRow> content = rows.getContent();
    Map<UUID, Long> itemCounts = itemCounts(content);
    Map<UUID, Long> spaceCounts = spaceCounts(content);
    Map<UUID, String> names = ownerNames.of(content);
    Map<UUID, SuccessionFinding> succession = successionSource.findingsAmong(content, false);
    Map<UUID, AssetRole> roles = roles(content, caller);
    Map<UUID, AssetCatalogFacts> facts = facts(content);
    Set<UUID> marked =
        content.isEmpty()
            ? Set.of()
            : favorites.findMarkedAmong(
                caller.id(), content.stream().map(AssetCatalogRow::getId).toList());
    List<AssetCatalogEntry> entries =
        content.stream()
            .map(
                row -> {
                  UUID id = row.getId();
                  AssetCatalogFacts rowFacts = facts.get(id);
                  return new AssetCatalogEntry(
                      row,
                      Optional.ofNullable(roles.get(id)).orElse(AssetRole.VIEWER),
                      selection.publicIds().contains(id)
                          ? CatalogVisibility.PUBLIC
                          : CatalogVisibility.RESTRICTED,
                      statusOf(succession.get(id), rowFacts),
                      rowFacts,
                      names.get(row.getOwnerId()),
                      succession.get(id),
                      itemCounts.getOrDefault(id, 0L),
                      spaceCounts.getOrDefault(id, 0L),
                      marked.contains(id));
                })
            .toList();
    return new AssetCatalogPage(entries, page, size, rows.getTotalElements(), rows.getTotalPages());
  }

  /** The ids the page query may return, and which of the readable ones are public. */
  private record Selection(Set<UUID> ids, Set<UUID> publicIds) {}

  /**
   * The readable assets of {@code types}, narrowed by the visibility and the group filter of {@code
   * query}. "Aus meinen Gruppen" is a grant to one of the caller's groups or ownership by one.
   */
  private Selection select(CurrentUser caller, List<AssetType> types, AssetCatalogQuery query) {
    Set<UUID> readable = new HashSet<>();
    Set<UUID> publicIds = new HashSet<>();
    Set<UUID> grantedToMyGroups = new HashSet<>();
    Set<UUID> myGroupIds = new HashSet<>();
    for (AssetType type : types) {
      ReadableAssets reach =
          accessService.readableAssets(type, caller.id(), caller.organizationId());
      readable.addAll(reach.all());
      publicIds.addAll(reach.byAllAccountsGrant());
      grantedToMyGroups.addAll(reach.byGroupGrant());
      myGroupIds.addAll(reach.callerGroupIds());
    }

    Set<UUID> selected = new HashSet<>(readable);
    if (query.visibility() == CatalogVisibility.PUBLIC) {
      selected.retainAll(publicIds);
    } else if (query.visibility() == CatalogVisibility.RESTRICTED) {
      selected.removeAll(publicIds);
    }
    if (query.fromMyGroups()) {
      Set<UUID> mine = new HashSet<>(grantedToMyGroups);
      if (!myGroupIds.isEmpty() && !selected.isEmpty()) {
        mine.addAll(
            assetRepository.findIdsOwnedByGroups(caller.organizationId(), types, myGroupIds));
      }
      selected.retainAll(mine);
    }
    return new Selection(selected, publicIds);
  }

  private static CatalogEntryStatus statusOf(
      SuccessionFinding succession, AssetCatalogFacts facts) {
    if (succession != null) {
      return CatalogEntryStatus.SUCCESSION_OPEN;
    }
    return facts == null ? CatalogEntryStatus.READY : facts.status();
  }

  /** The caller's role on each asset of the page by the formula, one query per type. */
  private Map<UUID, AssetRole> roles(List<AssetCatalogRow> rows, CurrentUser caller) {
    Map<UUID, AssetRole> roles = new HashMap<>();
    idsByType(rows)
        .forEach((type, ids) -> roles.putAll(accessService.effectiveRoles(type, ids, caller.id())));
    return roles;
  }

  /** The type-specific facts of the page's assets, from the source of each type present. */
  private Map<UUID, AssetCatalogFacts> facts(List<AssetCatalogRow> rows) {
    Map<UUID, AssetCatalogFacts> facts = new HashMap<>();
    idsByType(rows)
        .forEach(
            (type, ids) ->
                Optional.ofNullable(factSources.get(type))
                    .ifPresent(source -> facts.putAll(source.factsOf(ids))));
    return facts;
  }

  /** The extent of the page's assets, one grouped query per type present on it. */
  private Map<UUID, Long> itemCounts(List<AssetCatalogRow> rows) {
    Map<UUID, Long> counts = new HashMap<>();
    idsByType(rows)
        .forEach(
            (type, ids) ->
                Optional.ofNullable(extents.get(type))
                    .ifPresent(extent -> counts.putAll(extent.itemCounts(ids))));
    return counts;
  }

  private static Map<AssetType, Set<UUID>> idsByType(List<AssetCatalogRow> rows) {
    return rows.stream()
        .collect(
            Collectors.groupingBy(
                AssetCatalogRow::getAssetType,
                Collectors.mapping(AssetCatalogRow::getId, Collectors.toSet())));
  }

  private Map<UUID, Long> spaceCounts(List<AssetCatalogRow> rows) {
    if (rows.isEmpty()) {
      return Map.of();
    }
    return assetRepository
        .countSpaceAssociations(rows.stream().map(AssetCatalogRow::getId).toList())
        .stream()
        .collect(
            Collectors.toMap(
                AssetRepository.AssetSpaceCount::getAssetId,
                AssetRepository.AssetSpaceCount::getSpaceCount));
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
