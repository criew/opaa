import {
  EMPTY_SHAREPOINT_VALUES,
  sharePointLibrariesOf,
  sharePointLibraryLabel,
  sharePointPayloadOf,
  sharePointSettingsFromLibrary,
  sharePointWholeSitesOf,
  type SharePointSourceValues,
} from '../../utils/sharePointSource'
import { sharePointSetup } from '../setup/connectors/sharePointSetup'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

export const sharePointSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.SHAREPOINT,
  containerLabel: 'Dokumentbibliothek',
  configuration: {
    empty: EMPTY_SHAREPOINT_VALUES,
    draftFields: ['allLibraries', 'sites', 'libraries', 'fullSyncIntervalDays'],
    fromLibrary: (library): SharePointSourceValues => {
      const settings = sharePointSettingsFromLibrary(library)
      const sites = sharePointWholeSitesOf(settings)
      return {
        allLibraries: sites.length > 0,
        sites,
        libraries: sharePointLibrariesOf(settings),
        fullSyncIntervalDays: settings?.fullSyncIntervalDays ?? null,
      }
    },
    isDirty: (values: SharePointSourceValues) =>
      values.libraries.length > 0 || values.sites.length > 0,
    // ADR-0047: only a profile signs in; the library holds no secret
    toPayload: sharePointPayloadOf,
    nameFromSource: (values: SharePointSourceValues) => {
      if (values.allLibraries) {
        return values.sites.length === 1 && values.sites[0].name ? values.sites[0].name : ''
      }
      return values.libraries.length === 1 ? sharePointLibraryLabel(values.libraries[0]) : ''
    },
    scopeNoteId: 'sharepoint-sharing-consequence',
    fullSyncRhythm: true,
    rhythmSettingsBase: (library) => library.sourceSettings ?? null,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle Dokumentbibliotheken; danach liest jeder Lauf nur das Änderungsprotokoll von Microsoft Graph.',
    runsHint:
      '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ alle Dokumentbibliotheken neu.',
    setup: sharePointSetup,
  },
}
