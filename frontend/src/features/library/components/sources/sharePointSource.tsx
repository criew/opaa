import {
  EMPTY_SHAREPOINT_VALUES,
  sharePointLibrariesOf,
  sharePointCoverageLabel,
  sharePointLibraryLabel,
  sharePointPayloadOf,
  sharePointSettingsFromLibrary,
  validateSharePointValues,
  type SharePointSourceValues,
} from '../../utils/sharePointSource'
import SharePointForm from '../sourceForms/SharePointForm'
import { listSummary } from '../sourceKit/summaries'
import { SharePointScopeView, SharePointStoredView } from './SharePointSourceViews'
import { formIssue, issueAt } from '../../utils/sourceIssue'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration, StoredLibrarySource } from './types'

const selectionIssue = (message: string | null) => (message ? issueAt('selection', message) : null)

/** The name a run's container key (`drive:<id>`) has in the stored settings, the key otherwise. */
function containerName(key: string, library: StoredLibrarySource) {
  const driveId = key.startsWith('drive:') ? key.slice('drive:'.length) : null
  const match = sharePointLibrariesOf(sharePointSettingsFromLibrary(library)).find(
    (entry) => entry.driveId === driveId,
  )
  return match ? sharePointLibraryLabel(match) : key
}

export const sharePointSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.SHAREPOINT,
  containerLabel: 'Dokumentbibliothek',
  configuration: {
    empty: EMPTY_SHAREPOINT_VALUES,
    draftFields: ['libraries', 'fullSyncIntervalDays'],
    fromLibrary: (library): SharePointSourceValues => {
      const settings = sharePointSettingsFromLibrary(library)
      return {
        libraries: sharePointLibrariesOf(settings),
        fullSyncIntervalDays: settings?.fullSyncIntervalDays ?? null,
      }
    },
    isDirty: (values: SharePointSourceValues) => values.libraries.length > 0,
    // ADR-0047: only a profile signs in; the library holds no secret
    validate: (values: SharePointSourceValues, context) =>
      context.mode === 'create' && !context.connection
        ? formIssue('SharePoint ist nur über einen Zugang nutzbar. Bitte einen Zugang wählen.')
        : selectionIssue(validateSharePointValues(values)),
    issues: (values: SharePointSourceValues, context) => {
      const issue =
        context.mode === 'create' && !context.connection
          ? formIssue('SharePoint ist nur über einen Zugang nutzbar. Bitte einen Zugang wählen.')
          : selectionIssue(validateSharePointValues(values))
      return issue ? [issue] : []
    },
    summary: (values: SharePointSourceValues) => [
      listSummary(
        'Dokumentbibliotheken',
        values.libraries.map(
          (library) => `${sharePointLibraryLabel(library)} (${sharePointCoverageLabel(library)})`,
        ),
        'Keine',
      ),
    ],
    toPayload: sharePointPayloadOf,
    nameFromSource: (values: SharePointSourceValues) =>
      values.libraries.length === 1 ? sharePointLibraryLabel(values.libraries[0]) : '',
    Form: SharePointForm,
    StoredView: SharePointStoredView,
    Scope: SharePointScopeView,
    scopeHero: {
      summary: (library) => {
        const count = sharePointLibrariesOf(sharePointSettingsFromLibrary(library)).length
        return count === 1 ? '1 Dokumentbibliothek' : `${count} Dokumentbibliotheken`
      },
      unlistedWarning: (keys, library) => {
        const names = keys.map((key) => `„${containerName(key, library)}“`)
        return (
          (names.length === 1
            ? `Der letzte Lauf konnte die Dokumentbibliothek ${names[0]} nicht erreichen; ihr Bestand ist möglicherweise veraltet.`
            : `Der letzte Lauf konnte die Dokumentbibliotheken ${names.join(', ')} nicht erreichen; ihr Bestand ist möglicherweise veraltet.`) +
          ' Der Hinweis bleibt, bis ein Vollabgleich wieder alle Dokumentbibliotheken auflisten kann.'
        )
      },
      unlistedTestId: 'sharepoint-incomplete-listing-warning',
    },
    fullSyncRhythm: true,
    rhythmSettingsBase: (library) => library.sourceSettings ?? null,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle Dokumentbibliotheken; danach liest jeder Lauf nur das Änderungsprotokoll von Microsoft Graph.',
    runsHint:
      '„Jetzt indizieren“ liest in der Regel nur das Änderungsprotokoll seit dem letzten Lauf. Nach einer Änderung der Auswahl und im eingestellten Abstand läuft es automatisch als Vollabgleich, der alle Dokumentbibliotheken vollständig listet, umbenannte oder verschobene Ordner nachführt und entfernt, was in SharePoint nicht mehr vorhanden ist. „Vollabgleich starten“ erzwingt ihn sofort.',
  },
}
