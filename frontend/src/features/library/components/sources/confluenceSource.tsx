import {
  confluenceCredentialsOf,
  confluenceSettingsOf,
  confluenceSpaceLabel,
  EMPTY_CONFLUENCE_VALUES,
  confluenceIssue,
  type ConfluenceSourceValues,
} from '../../utils/confluenceSource'
import { confluenceEditionLabel } from '../../utils/confluenceSource'
import ConfluenceSourceForm from '../ConfluenceSourceForm'
import { ConfluenceScope, ConfluenceStoredView } from './ConfluenceSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

export const confluenceSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.CONFLUENCE,
  containerLabel: 'Space',
  opensAtSource: true,
  configuration: {
    empty: EMPTY_CONFLUENCE_VALUES,
    draftFields: ['sourceUrl', 'edition', 'email', 'spaces'],
    addressDerived: ['edition', 'credentialsVerified'],
    // ADR-0023: the edition is fixed, the stored credentials stand until new ones are typed, and
    // the current selection is the starting point.
    fromLibrary: (library): ConfluenceSourceValues => ({
      ...EMPTY_CONFLUENCE_VALUES,
      sourceUrl: library.sourceUrl ?? '',
      edition: confluenceSettingsOf(library).edition ?? null,
      credentialsVerified: Boolean(library.sourceCredentialsSet),
      spaces: confluenceSettingsOf(library).spaces ?? [],
      everything: confluenceSettingsOf(library).allSpaces === true,
    }),
    isDirty: (values: ConfluenceSourceValues) => values.sourceUrl !== '',
    validate: (values: ConfluenceSourceValues) => confluenceIssue(values),
    toPayload: (values: ConfluenceSourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: confluenceCredentialsOf(values),
      sourceSettings: {
        edition: values.edition ?? undefined,
        ...(values.everything
          ? { allSpaces: true, spaces: [] }
          : {
              spaces: values.spaces.map((space) => ({ key: space.key, name: space.name ?? null })),
            }),
      },
    }),
    nameFromSource: (values: ConfluenceSourceValues) => {
      const first = values.spaces[0]
      if (!first) return ''
      return values.spaces.length === 1 ? (first.name ?? first.key) : ''
    },
    Form: ({ values, onChange, context }) => (
      <ConfluenceSourceForm
        mode={context.mode}
        idPrefix={`${context.idPrefix}-confluence`}
        libraryId={context.libraryId}
        credentialsStored={context.credentialsStored}
        originalSourceUrl={context.originalSourceUrl}
        connection={connectionFields(context)}
        values={values}
        onChange={onChange}
      />
    ),
    StoredView: ConfluenceStoredView,
    Scope: ConfluenceScope,
    scopeHero: {
      summary: (library) => {
        const settings = confluenceSettingsOf(library)
        const count = settings.spaces?.length ?? 0
        return [
          count === 1 ? '1 Space' : `${count} Spaces`,
          settings.edition ? confluenceEditionLabel(settings.edition) : null,
        ]
          .filter(Boolean)
          .join(' · ')
      },
      unlistedWarning: (keys, library) => {
        const spaces = confluenceSettingsOf(library).spaces
        return keys.length === 1
          ? `Der letzte Vollabgleich konnte den Space ${confluenceSpaceLabel(keys[0], spaces)} nicht vollständig lesen; sein Bestand ist möglicherweise veraltet. Der Hinweis bleibt, bis ein Vollabgleich wieder alle Spaces lesen kann.`
          : `Der letzte Vollabgleich konnte die Spaces ${keys.map((key) => confluenceSpaceLabel(key, spaces)).join(', ')} nicht vollständig lesen; ihr Bestand ist möglicherweise veraltet. Der Hinweis bleibt, bis ein Vollabgleich wieder alle Spaces lesen kann.`
      },
      unlistedTestId: 'confluence-incomplete-listing-warning',
    },
    fullSyncRhythm: true,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle ausgewählten Spaces; sein Stand bleibt auf der Detailseite sichtbar.',
    runsHint: '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ alle Spaces neu.',
  },
}
