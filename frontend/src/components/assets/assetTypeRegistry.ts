import type { SvgIconComponent } from '@mui/icons-material'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import TextSnippetOutlinedIcon from '@mui/icons-material/TextSnippetOutlined'
import { useMemo } from 'react'
import { CATALOG_ROUTE, promptLibraryRoute } from '../../routes'
import { useAuthStore } from '../../stores/authStore'
import type { AssetType, Capability } from '../../types/api'
import type { FeatureFlags } from '../../types/auth'
import { isAssetTypeOffered } from '../../utils/installationFeatures'

/** Everything the interface needs to know about one asset type. */
export interface AssetTypeDefinition {
  type: AssetType
  /** The value of the catalog's type filter in the address (`/catalog?type=…`). */
  slug: string
  /** The type as a group of things - type filter and type choice ("Wissen"). */
  label: string
  /** The name of one asset's type, as the type badge shows it ("Wissen", "Prompts"). */
  title: string
  /** The singular noun as a sentence names it ("diese Bibliothek"). */
  noun: string
  /** One sentence under the label in the type choice. */
  description: string
  Icon: SvgIconComponent
  /**
   * The path prefix of the type's own pages (detail and wizard). They render in the global frame
   * and count as the catalog's scope in the rail.
   */
  routePrefix: string
  detailRoute: (assetId: string) => string
  /** Where the type's own creation wizard starts. */
  createRoute: string
  /** Holding any one of them is enough to be offered the type under "Neu". */
  createCapabilities: Capability[]
  /** What the asset holds, in the words of the type ("12 Prompts"). */
  extentLabel: (count: number) => string
}

/**
 * The asset types in the order of the catalog's filter and the type choice. A new type needs one
 * entry here to appear in the catalog, its type filter and under "Neu", and for its pages to
 * render in the global frame under the rail's "Katalog" - no rail entry, no overview page of its
 * own. Its routes themselves still need registering in App.tsx. Every list the interface shows
 * reads the active types ({@link activeAssetTypes}, {@link useActiveAssetTypes}), never this one.
 */
export const ASSET_TYPES: AssetTypeDefinition[] = [
  {
    type: 'KNOWLEDGE_LIBRARY',
    slug: 'knowledge',
    label: 'Wissen',
    title: 'Wissen',
    noun: 'Bibliothek',
    description:
      'Dokumente, die der Chat durchsucht – hochgeladen oder aus einer Quelle eingelesen.',
    Icon: MenuBookOutlinedIcon,
    routePrefix: '/libraries',
    detailRoute: (assetId) => `/libraries/${assetId}`,
    createRoute: '/libraries/new',
    createCapabilities: ['CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY'],
    extentLabel: (count) => (count === 1 ? '1 Dokument' : `${count} Dokumente`),
  },
  {
    type: 'PROMPT_LIBRARY',
    slug: 'prompts',
    label: 'Prompts',
    title: 'Prompts',
    noun: 'Prompt-Bibliothek',
    description: 'Wiederkehrende Formulierungshilfen mit Platzhaltern, im Chat per „/“ einsetzbar.',
    Icon: TextSnippetOutlinedIcon,
    routePrefix: '/prompts',
    detailRoute: (assetId) => promptLibraryRoute(assetId),
    createRoute: '/prompts/new',
    createCapabilities: ['CREATE_PROMPT_LIBRARY'],
    extentLabel: (count) => (count === 1 ? '1 Prompt' : `${count} Prompts`),
  },
]

/**
 * The types whose installation-wide feature (ADR-0044, `ASSET_TYPE_FEATURES`) is on; a type behind
 * a switched-off feature is offered nowhere - no filter, tile, "Neu" option, picker or page.
 */
function activeIn(features: FeatureFlags): AssetTypeDefinition[] {
  return ASSET_TYPES.filter((definition) => isAssetTypeOffered(definition.type, features))
}

/** The types the installation offers, for code outside React; components use the hook. */
export function activeAssetTypes(): AssetTypeDefinition[] {
  return activeIn(useAuthStore.getState().features)
}

/** The types the installation offers; re-renders when the feature flags are read again. */
export function useActiveAssetTypes(): AssetTypeDefinition[] {
  const features = useAuthStore((state) => state.features)
  return useMemo(() => activeIn(features), [features])
}

/** Whether the installation offers the type at all. */
export function isAssetTypeActive(assetType: AssetType | string | null | undefined): boolean {
  return assetTypeDefinition(assetType) !== undefined
}

/** The definition of an offered type; a type behind a switched-off feature has none. */
export function assetTypeDefinition(
  assetType: AssetType | string | null | undefined,
): AssetTypeDefinition | undefined {
  return activeAssetTypes().find((definition) => definition.type === assetType)
}

/** The singular noun of an asset type, as a sentence names it ("diese Bibliothek"). */
export function assetTypeLabel(assetType: AssetType | string | null | undefined): string {
  return assetTypeDefinition(assetType)?.noun ?? assetType ?? ''
}

/** The name of an asset type, as the type marker of a mixed list shows it. */
export function assetTypeTitle(assetType: AssetType | string | null | undefined): string {
  return assetTypeDefinition(assetType)?.title ?? assetType ?? ''
}

/**
 * The types offered under "Neu": those for which the person is not known to lack every creation
 * right. While the rights are still loading, every type counts as offered.
 */
export function creatableAssetTypes(
  isMissing: (capability: Capability) => boolean,
): AssetTypeDefinition[] {
  return activeAssetTypes().filter((definition) =>
    definition.createCapabilities.some((capability) => !isMissing(capability)),
  )
}

/** The catalog and the pages of every asset type - the rail's "Katalog" scope. */
export function catalogScopePrefixes(): string[] {
  return [CATALOG_ROUTE, ...activeAssetTypes().map((definition) => definition.routePrefix)]
}

/** The catalog, narrowed to one type when given. */
export function catalogRoute(assetType?: AssetType): string {
  const slug = assetTypeDefinition(assetType)?.slug
  return slug ? `${CATALOG_ROUTE}?type=${slug}` : CATALOG_ROUTE
}
