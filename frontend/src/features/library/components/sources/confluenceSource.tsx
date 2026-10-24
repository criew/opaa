import {
  confluenceCredentialsOf,
  confluenceSettingsOf,
  confluenceSpaceLabel,
  EMPTY_CONFLUENCE_VALUES,
  confluenceIssue,
  type ConfluenceSourceValues,
} from '../../utils/confluenceSource'
import { confluenceEditionLabel } from '../../utils/confluenceSource'
import ConfluenceForm, { pickedOfSpaces } from '../setup/forms/ConfluenceForm'
import { issueAt, type SourceIssue } from '../../utils/sourceIssue'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import { MAX_CONFLUENCE_SPACES } from '../../utils/confluenceSource'
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
    }),
    isDirty: (values: ConfluenceSourceValues) => values.sourceUrl !== '',
    validate: (values: ConfluenceSourceValues) => confluenceIssue(values),
    toPayload: (values: ConfluenceSourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: confluenceCredentialsOf(values),
      sourceSettings: {
        edition: values.edition ?? undefined,
        spaces: values.spaces.map((space) => ({ key: space.key, name: space.name ?? null })),
      },
    }),
    nameFromSource: (values: ConfluenceSourceValues) => {
      const first = values.spaces[0]
      if (!first) return ''
      return values.spaces.length === 1 ? (first.name ?? first.key) : ''
    },
    validateAll: (values: ConfluenceSourceValues, context) => {
      const issues: SourceIssue[] = []
      const url = values.sourceUrl.trim()
      if (!url) issues.push(issueAt('sourceUrl', 'Bitte die Adresse eingeben'))
      else if (!/^https?:\/\//i.test(url)) {
        issues.push(issueAt('sourceUrl', 'Die Adresse muss mit http:// oder https:// beginnen'))
      }
      const stored =
        context.credentialsStored && sameLibrarySourceOrigin(context.originalSourceUrl, url)
      if (connectionFields(context).asksSecret) {
        if (values.edition !== 'DATA_CENTER' && values.email.trim() === '' && (!stored || values.token !== '')) {
          issues.push(issueAt('email', 'Bitte die E-Mail-Adresse des Kontos eingeben'))
        }
        if (values.token.trim() === '' && !stored) {
          issues.push(issueAt('token', 'Bitte das Token eingeben'))
        }
      }
      if (values.spaces.length === 0) {
        issues.push(issueAt('spaces', 'Bitte mindestens einen Space wählen'))
      } else if (values.spaces.length > MAX_CONFLUENCE_SPACES) {
        issues.push(issueAt('spaces', `Höchstens ${MAX_CONFLUENCE_SPACES} Spaces je Bibliothek`))
      }
      return issues
    },
    summary: (values: ConfluenceSourceValues, context) => ({
      connection: [
        { label: 'Adresse', value: values.sourceUrl.trim(), mono: true },
        { label: 'Edition', value: values.edition ? confluenceEditionLabel(values.edition) : '' },
        {
          label: 'Konto',
          value: values.email.trim() || (context.credentialsStored ? 'gespeichert' : ''),
        },
      ],
      selection: {
        rootLabel: 'Confluence',
        noun: { one: 'Space', many: 'Spaces' },
        items: pickedOfSpaces(values.spaces),
        empty: 'Kein Space gewählt',
      },
    }),
    Form: ConfluenceForm,
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
    runsHint:
      '„Jetzt indizieren“ nimmt in der Regel nur Änderungen seit dem letzten Lauf auf. Nach einer Änderung der Space-Auswahl und im vom Betrieb eingestellten Abstand läuft es automatisch als Vollabgleich — dieser prüft alle ausgewählten Spaces vollständig und entfernt, was in Confluence nicht mehr vorhanden ist. „Vollabgleich starten“ erzwingt ihn sofort.',
  },
}
