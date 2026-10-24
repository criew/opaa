import {
  confluenceCredentialsOf,
  confluenceSettingsOf,
  confluenceSpaceLabel,
  EMPTY_CONFLUENCE_VALUES,
  type ConfluenceSourceValues,
} from '../../utils/confluenceSource'
import { confluenceEditionLabel } from '../../utils/confluenceSource'
import ConfluenceForm from '../sourceForms/ConfluenceForm'
import { listSummary } from '../sourceKit/summaries'
import { detectedConfluenceEdition, MAX_CONFLUENCE_SPACES } from '../../utils/confluenceSource'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import type { SourceIssue } from '../../utils/sourceIssue'
import { ConfluenceScope, ConfluenceStoredView } from './ConfluenceSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceFormContext, SourceRegistration } from './types'

/**
 * Every issue at once. Edition and verified credentials are no issues of their own here: the
 * connection test before „Weiter“ detects the one and checks the other (Variante E).
 */
function confluenceIssues(
  values: ConfluenceSourceValues,
  context: SourceFormContext,
): SourceIssue[] {
  const issues: SourceIssue[] = []
  const url = values.sourceUrl.trim()
  if (url === '') issues.push({ field: 'sourceUrl', message: 'Bitte die Adresse eingeben.' })
  else if (!/^https?:\/\//i.test(url)) {
    issues.push({ field: 'sourceUrl', message: 'Die Adresse beginnt mit https:// oder http://.' })
  }
  const stored =
    context.mode === 'edit' &&
    context.credentialsStored &&
    sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)
  if (connectionFields(context).asksSecret && !stored) {
    if (values.edition !== 'DATA_CENTER' && values.email.trim() === '') {
      issues.push({ field: 'email', message: 'Bitte die Kennung eingeben.' })
    }
    if (values.token.trim() === '') {
      issues.push({ field: 'token', message: 'Bitte das Kennwort eingeben.' })
    }
  }
  if (values.spaces.length === 0) {
    issues.push({ field: 'selection', message: 'Bitte mindestens einen Space auswählen.' })
  } else if (values.spaces.length > MAX_CONFLUENCE_SPACES) {
    issues.push({
      field: 'selection',
      message: `Höchstens ${MAX_CONFLUENCE_SPACES} Spaces je Bibliothek.`,
    })
  }
  return issues
}

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
    issues: confluenceIssues,
    validate: (values: ConfluenceSourceValues, context) =>
      confluenceIssues(values, context)[0] ?? null,
    onTested: (details, values: ConfluenceSourceValues) => {
      const edition = detectedConfluenceEdition(details) ?? values.edition
      return edition ? { edition, credentialsVerified: true } : null
    },
    summary: (values: ConfluenceSourceValues) => [
      { label: 'Adresse', value: values.sourceUrl.trim() },
      ...(values.edition
        ? [{ label: 'Edition', value: confluenceEditionLabel(values.edition) }]
        : []),
      listSummary(
        'Spaces',
        values.spaces.map((space) => space.name ?? space.key),
        'Keine',
      ),
    ],
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
