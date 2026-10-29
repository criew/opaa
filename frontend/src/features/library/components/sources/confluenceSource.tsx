import {
  confluenceCredentialsOf,
  confluenceSettingsOf,
  EMPTY_CONFLUENCE_VALUES,
  type ConfluenceSourceValues,
} from '../../utils/confluenceSource'
import { confluenceSetup } from '../setup/connectors/confluenceSetup'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

export const confluenceSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.CONFLUENCE,
  containerLabel: 'Space',
  opensAtSource: true,
  configuration: {
    empty: EMPTY_CONFLUENCE_VALUES,
    draftFields: ['sourceUrl', 'edition', 'email', 'allSpaces', 'spaces'],
    addressDerived: ['edition', 'credentialsVerified'],
    // ADR-0023: the edition is fixed, the stored credentials stand until new ones are typed, and
    // the current selection is the starting point.
    fromLibrary: (library): ConfluenceSourceValues => ({
      ...EMPTY_CONFLUENCE_VALUES,
      sourceUrl: library.sourceUrl ?? '',
      edition: confluenceSettingsOf(library).edition ?? null,
      credentialsVerified: Boolean(library.sourceCredentialsSet),
      allSpaces: confluenceSettingsOf(library).allSpaces === true,
      spaces: confluenceSettingsOf(library).spaces ?? [],
    }),
    isDirty: (values: ConfluenceSourceValues) => values.sourceUrl !== '',
    toPayload: (values: ConfluenceSourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: confluenceCredentialsOf(values),
      sourceSettings: {
        edition: values.edition ?? undefined,
        ...(values.allSpaces ? { allSpaces: true } : {}),
        spaces: values.allSpaces
          ? []
          : values.spaces.map((space) => ({ key: space.key, name: space.name ?? null })),
      },
    }),
    nameFromSource: (values: ConfluenceSourceValues) => {
      const first = values.spaces[0]
      if (values.allSpaces || !first) return ''
      return values.spaces.length === 1 ? (first.name ?? first.key) : ''
    },
    scopeNoteId: 'confluence-sharing-consequence',
    fullSyncRhythm: true,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle Spaces des Umfangs; sein Stand bleibt auf der Detailseite sichtbar.',
    runsHint: '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ alle Spaces neu.',
    setup: confluenceSetup,
  },
}
