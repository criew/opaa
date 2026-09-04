import type { ConfluenceEdition, ConfluenceSpaceRef } from '../types/api'

/**
 * Everything the Confluence source configuration consists of (ADR-0023): the address, the edition
 * the connection test detected (fixed after creation), the credentials in the edition's shape, the
 * verification state and the space selection. Owned by the wizard or the edit dialog; the form
 * only proposes changes through onChange.
 */
export interface ConfluenceSourceValues {
  sourceUrl: string
  sourceProxy: string
  sourceInsecureSsl: boolean
  edition: ConfluenceEdition | null
  email: string
  token: string
  /** True once the instance accepted the credentials (or, in edit mode, while the stored ones stand). */
  credentialsVerified: boolean
  spaces: ConfluenceSpaceRef[]
}

/** Mirrors KnowledgeLibraryService.MAX_CONFLUENCE_SPACES - the backend rejects a larger selection with 400. */
export const MAX_CONFLUENCE_SPACES = 500

export const EMPTY_CONFLUENCE_VALUES: ConfluenceSourceValues = {
  sourceUrl: '',
  sourceProxy: '',
  sourceInsecureSsl: false,
  edition: null,
  email: '',
  token: '',
  credentialsVerified: false,
  spaces: [],
}

/** Joins the entered credentials into the stored form (ADR-0023, Entscheidung 3); undefined when none entered. */
export function confluenceCredentialsOf(values: ConfluenceSourceValues): string | undefined {
  const token = values.token.trim()
  if (!token) return undefined
  if (values.edition === 'CLOUD') {
    return `${values.email.trim()}:${token}`
  }
  return token
}

/**
 * The Confluence stages in order (ADR-0023): address, detected edition, verified credentials, at
 * least one space - the first missing one is the message, so the wizard points at the next step.
 */
export function validateConfluenceValues(
  values: ConfluenceSourceValues | undefined,
): string | null {
  if (!values) return 'Adresse der Confluence-Instanz ist erforderlich'
  const url = values.sourceUrl.trim()
  if (!url) return 'Adresse der Confluence-Instanz ist erforderlich'
  if (!/^https?:\/\//i.test(url)) {
    return 'Adresse der Confluence-Instanz muss mit http:// oder https:// beginnen'
  }
  if (!values.edition) {
    return 'Bitte zuerst die Edition erkennen lassen („Edition erkennen“)'
  }
  if (!values.credentialsVerified) {
    return 'Bitte die Zugangsdaten mit „Verbindung testen“ prüfen, bevor Sie fortfahren'
  }
  if (values.spaces.length === 0) {
    return 'Bitte mindestens einen Space auswählen'
  }
  if (values.spaces.length > MAX_CONFLUENCE_SPACES) {
    return `Höchstens ${MAX_CONFLUENCE_SPACES} Spaces je Bibliothek`
  }
  return null
}
