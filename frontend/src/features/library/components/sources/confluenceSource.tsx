import {
  confluenceCredentialsOf,
  confluenceSettingsOf,
  confluenceSpaceLabel,
  EMPTY_CONFLUENCE_VALUES,
  confluenceIssue,
  type ConfluenceSourceValues,
} from '../../utils/confluenceSource'
import { confluenceEditionLabel } from '../../utils/confluenceSource'
import ConfluenceSetup from '../setup/ConfluenceSetup'
import { addressIssue } from '../setup/validation'
import { collectIssues } from '../setup/setupContext'
import { listed } from '../setup/summaries'
import { issueAt } from '../../utils/sourceIssue'
import { MAX_CONFLUENCE_SPACES } from '../../utils/confluenceSource'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
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
      const kept =
        !connectionFields(context).asksSecret ||
        (context.credentialsStored &&
          sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl))
      const cloud = values.edition !== 'DATA_CENTER'
      return collectIssues(
        addressIssue('sourceUrl', values.sourceUrl),
        !kept && cloud && values.email.trim() === ''
          ? issueAt('email', 'Bitte die E-Mail-Adresse eingeben.')
          : null,
        !kept && values.token.trim() === '' ? issueAt('token', 'Bitte das Token eingeben.') : null,
        values.spaces.length === 0
          ? issueAt('spaces', 'Bitte mindestens einen Space wählen.')
          : null,
        values.spaces.length > MAX_CONFLUENCE_SPACES
          ? issueAt('spaces', `Höchstens ${MAX_CONFLUENCE_SPACES} Spaces je Bibliothek.`)
          : null,
      )
    },
    checkStages: ['address', 'signIn', 'access'],
    summary: (values: ConfluenceSourceValues) => ({
      connection: [
        { label: 'Adresse', value: values.sourceUrl.trim(), mono: true },
        {
          label: 'Edition',
          value: values.edition
            ? `Confluence ${confluenceEditionLabel(values.edition)}`
            : 'unbekannt',
        },
      ],
      credentials: [
        {
          label: values.edition === 'DATA_CENTER' ? 'Token' : 'E-Mail-Adresse und Token',
          value: values.email ? `${values.email} · Token gesetzt` : 'gesetzt',
        },
      ],
      scope: [
        {
          label: values.spaces.length === 1 ? 'Space' : `Spaces (${values.spaces.length})`,
          value: listed(values.spaces.map((space) => space.name ?? space.key)),
        },
      ],
    }),
    Form: ConfluenceSetup,
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
