import type { SvgIconComponent } from '@mui/icons-material'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import TextSnippetOutlinedIcon from '@mui/icons-material/TextSnippetOutlined'
import { CATALOG_ROUTE, promptLibraryRoute } from '../../routes'
import type { AssetType, Capability } from '../../types/api'

/** Everything the interface needs to know about one asset type. */
export interface AssetTypeDefinition {
  type: AssetType
  /** The value of the catalog's type filter in the address (`/catalog?type=…`). */
  slug: string
  /** The type as a group of things - type filter and type choice ("Wissen"). */
  label: string
  /** The full name of one asset, as the type badge shows it ("Wissensbibliothek"). */
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
 * own. Its routes themselves still need registering in App.tsx.
 */
export const ASSET_TYPES: AssetTypeDefinition[] = [
  {
    type: 'KNOWLEDGE_LIBRARY',
    slug: 'knowledge',
    label: 'Wissen',
    title: 'Wissensbibliothek',
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
    title: 'Prompt-Bibliothek',
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

export function assetTypeDefinition(
  assetType: AssetType | string | null | undefined,
): AssetTypeDefinition | undefined {
  return ASSET_TYPES.find((definition) => definition.type === assetType)
}

/** The singular noun of an asset type, as a sentence names it ("diese Bibliothek"). */
export function assetTypeLabel(assetType: AssetType | string | null | undefined): string {
  return assetTypeDefinition(assetType)?.noun ?? assetType ?? ''
}

/** The full name of an asset type, as the type marker of a mixed list shows it. */
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
  return ASSET_TYPES.filter((definition) =>
    definition.createCapabilities.some((capability) => !isMissing(capability)),
  )
}

/** The catalog and the pages of every asset type - the rail's "Katalog" scope. */
export function catalogScopePrefixes(): string[] {
  return [CATALOG_ROUTE, ...ASSET_TYPES.map((definition) => definition.routePrefix)]
}

/** The catalog, narrowed to one type when given. */
export function catalogRoute(assetType?: AssetType): string {
  const slug = assetTypeDefinition(assetType)?.slug
  return slug ? `${CATALOG_ROUTE}?type=${slug}` : CATALOG_ROUTE
}
