import {
  EMPTY_SHAREPOINT_VALUES,
  sharePointLibrariesOf,
  sharePointLibraryLabel,
  sharePointPayloadOf,
  sharePointSettingsFromLibrary,
  validateSharePointValues,
  type SharePointSourceValues,
} from '../../../utils/sharePointSource'
import SharePointSourceForm from '../SharePointSourceForm'
import { SharePointScopeView, SharePointStoredView } from './SharePointSourceViews'
import { formIssue, formIssueOf } from '../../../utils/sourceIssue'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '../../admin/connections/sourceTypeDirectory'
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
    // ADR-0040, Nachtrag „SharePoint“: only a profile signs in; the library holds no secret
    validate: (values: SharePointSourceValues, context) =>
      context.mode === 'create' && !context.connection
        ? formIssue('SharePoint ist nur über einen Zugang nutzbar. Bitte einen Zugang wählen.')
        : formIssueOf(validateSharePointValues(values)),
    toPayload: sharePointPayloadOf,
    nameFromSource: (values: SharePointSourceValues) =>
      values.libraries.length === 1 ? sharePointLibraryLabel(values.libraries[0]) : '',
    Form: ({ values, onChange, context }) => (
      <SharePointSourceForm
        // listings belong to the profile they were made through; another one starts them afresh
        key={context.connection?.profileId ?? 'no-profile'}
        mode={context.mode}
        idPrefix={`${context.idPrefix}-sharepoint`}
        libraryId={context.libraryId}
        connection={connectionFields(context)}
        values={values}
        onChange={onChange}
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
