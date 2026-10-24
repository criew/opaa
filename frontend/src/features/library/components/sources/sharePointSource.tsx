import {
  EMPTY_SHAREPOINT_VALUES,
  sharePointLibrariesOf,
  sharePointLibraryLabel,
  sharePointPayloadOf,
  sharePointSettingsFromLibrary,
  validateSharePointValues,
  type SharePointSourceValues,
} from '../../utils/sharePointSource'
import SharePointSetup from '../setup/SharePointSetup'
import { listed } from '../setup/summaries'
import { issueAt } from '../../utils/sourceIssue'
import { SharePointScopeView, SharePointStoredView } from './SharePointSourceViews'
import { formIssue, formIssueOf } from '../../utils/sourceIssue'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration, StoredLibrarySource } from './types'

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
        : formIssueOf(validateSharePointValues(values)),
    toPayload: sharePointPayloadOf,
    nameFromSource: (values: SharePointSourceValues) =>
      values.libraries.length === 1 ? sharePointLibraryLabel(values.libraries[0]) : '',
    validateAll: (values: SharePointSourceValues, context) => {
      if (context.mode === 'create' && !context.connection) {
        return [
          formIssue('SharePoint ist nur über einen Zugang nutzbar. Bitte einen Zugang wählen.'),
        ]
      }
      const message = validateSharePointValues(values)
      return message ? [issueAt('libraries', message)] : []
    },
    checkStages: ['signIn', 'access'],
    summary: (values: SharePointSourceValues, context) => ({
      connection: [{ label: 'Zugang', value: context.connection?.name ?? 'Microsoft 365' }],
      scope:
        values.libraries.length === 0
          ? []
          : [
              {
                label:
                  values.libraries.length === 1
                    ? 'Dokumentbibliothek'
                    : `Dokumentbibliotheken (${values.libraries.length})`,
                value: listed(
                  values.libraries.map((library) =>
                    library.folders.length > 0
                      ? `${sharePointLibraryLabel(library)}: ${library.folders.length} Ordner`
                      : sharePointLibraryLabel(library),
                  ),
                ),
              },
            ],
    }),
    Form: ({ values, onChange, context }) => (
      <SharePointSetup
        // listings belong to the profile they were made through; another one starts them afresh
        key={context.connection?.profileId ?? 'no-profile'}
        values={values}
        onChange={onChange}
        context={context}
      />
    ),
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
