import type { ConfluenceEdition, ConfluenceSpaceRef, DocumentSourceType } from '../types/api'
import {
  confluenceCredentialsOf,
  validateConfluenceValues,
  type ConfluenceSourceValues,
} from './confluenceSource'
import { documentSourceTypeConfigKind } from './labels'

/** Raw, untyped field state as entered in LibraryCreatePage/EditLibrarySourceDialog. */
export interface LibrarySourceFieldValues {
  sourcePath: string
  sourceUrl: string
  sourceProxy: string
  sourceCredentials: string
  sourceInsecureSsl: boolean
  /** Only read for configKind 'confluence' (ADR-0023). */
  confluence?: ConfluenceSourceValues
}

/**
 * The source configuration fields shared by LibraryRequest and LibraryUpdateRequest: the five
 * generic ones plus the two Confluence-only ones (ADR-0023), which stay undefined for every other
 * source type.
 */
export interface LibrarySourceConfigPayload {
  sourcePath?: string
  sourceUrl?: string
  sourceProxy?: string
  sourceCredentials?: string
  sourceInsecureSsl: boolean
  confluenceEdition?: ConfluenceEdition
  confluenceSpaces?: ConfluenceSpaceRef[]
}

/**
 * Client-side validation shared by LibraryCreatePage and EditLibrarySourceDialog (#516/#542
 * review, nit 4 - both dialogs previously carried a byte-identical copy of this check). A
 * stricter server-side check always runs afterwards regardless
 * (KnowledgeLibraryService#validateConfigurationForType for saving, SourceConnectionTestService
 * for the connection test) - this is only the fast, obvious-typo rejection every entry point
 * wants before making a network call at all. Returns a German error message on the first
 * violation, or null if the typed fields are acceptable for sourceType.
 */
export function validateLibrarySourceFields(
  sourceType: DocumentSourceType,
  values: Pick<LibrarySourceFieldValues, 'sourcePath' | 'sourceUrl' | 'confluence'>,
): string | null {
  const configKind = documentSourceTypeConfigKind[sourceType]
  if (configKind === 'confluence') {
    return validateConfluenceValues(values.confluence)
  }
  const trimmedPath = values.sourcePath.trim()
  if (configKind === 'path' && !trimmedPath) {
    return 'Verzeichnispfad ist erforderlich'
  }
  if (configKind === 'path' && !trimmedPath.startsWith('/')) {
    return 'Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente'
  }
  const trimmedUrl = values.sourceUrl.trim()
  if (configKind === 'url' && !trimmedUrl) {
    return 'Adresse (URL) ist erforderlich'
  }
  if (configKind === 'url' && trimmedUrl && !/^https?:\/\//i.test(trimmedUrl)) {
    return 'Adresse (URL) muss mit http:// oder https:// beginnen'
  }
  return null
}

/**
 * Derives the five typed source configuration fields to send in a LibraryRequest/
 * LibraryUpdateRequest body from raw field state (#516/#542 review, nit 4), mirroring
 * KnowledgeLibraryService#validateConfigurationForType's per-type shape: only the fields that
 * apply to sourceType are populated, everything else stays undefined so the backend's own
 * type-bound validation remains the single source of truth for what is allowed. Call {@link
 * validateLibrarySourceFields} first - this function does not itself reject anything.
 */
export function deriveLibrarySourceConfigPayload(
  sourceType: DocumentSourceType,
  values: LibrarySourceFieldValues,
): LibrarySourceConfigPayload {
  const configKind = documentSourceTypeConfigKind[sourceType]
  if (configKind === 'confluence' && values.confluence) {
    const c = values.confluence
    return {
      sourceUrl: c.sourceUrl.trim(),
      sourceProxy: c.sourceProxy.trim() || undefined,
      sourceCredentials: confluenceCredentialsOf(c),
      sourceInsecureSsl: c.sourceInsecureSsl,
      confluenceEdition: c.edition ?? undefined,
      confluenceSpaces: c.spaces.map((space) => ({ key: space.key, name: space.name ?? null })),
    }
  }
  return {
    sourcePath: configKind === 'path' ? values.sourcePath.trim() : undefined,
    sourceUrl: configKind === 'url' ? values.sourceUrl.trim() : undefined,
    sourceProxy:
      configKind === 'url' && values.sourceProxy.trim() ? values.sourceProxy.trim() : undefined,
    sourceCredentials:
      configKind === 'url' && values.sourceCredentials.trim()
        ? values.sourceCredentials.trim()
        : undefined,
    sourceInsecureSsl: configKind === 'url' ? values.sourceInsecureSsl : false,
  }
}

/**
 * Whether `previousUrl` and `nextUrl` name the same origin (scheme, host and port) - the frontend
 * counterpart of KnowledgeLibraryService#sameSourceOrigin (#542 review finding 1), used only to
 * phrase an accurate hint in EditLibrarySourceDialog about whether a stored credential survives a
 * URL edit. The backend re-derives this itself from the persisted value and is the only
 * authoritative check; a mismatch here (e.g. a stale client) only produces a wrong hint, never a
 * wrong outcome.
 */
export function sameLibrarySourceOrigin(
  previousUrl: string | null | undefined,
  nextUrl: string,
): boolean {
  const trimmedNext = nextUrl.trim()
  if (!previousUrl || !trimmedNext) {
    return false
  }
  try {
    return new URL(previousUrl).origin === new URL(trimmedNext).origin
  } catch {
    return false
  }
}
