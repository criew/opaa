import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { browseSource, testLibrarySource } from '../../../api/libraryApi'
import {
  MAX_SHAREPOINT_LIBRARIES,
  SHAREPOINT_API,
  sharePointFolderPath,
  sharePointKeyOf,
  sharePointLibraryChecksOf,
  sharePointLibraryName,
  type SharePointSourceValues,
} from '../../../utils/sharePointSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceFormProps } from '../../sources/types'
import ConnectionStage from '../ConnectionStage'
import { outcomeOf, outcomeOfError, type ConnectionOutcome } from '../connectionOutcome'
import { librariesOfPicked, pickedOfLibraries } from '../pickedOf'
import SplitPicker from '../SplitPicker'
import type { PickerCrumb, PickerLevel } from '../pickerTypes'
import { SelectionPending } from './AccountFolderForm'

const LEVELS = [
  { one: 'Site', many: 'Sites' },
  { one: 'Dokumentbibliothek', many: 'Dokumentbibliotheken' },
  { one: 'Ordner', many: 'Ordner' },
]

/**
 * The source form of a SharePoint library: the connection is the profile's app registration, so
 * „Verbindung" holds no fields; the selection walks Site → Dokumentbibliothek → Ordner, with the
 * parent level as its own column on wide screens. The site level takes a name or a site address.
 */
export default function SharePointForm({
  values,
  onChange,
  context,
}: SourceFormProps<SharePointSourceValues>) {
  const id = `${context.idPrefix}-sharepoint`
  const connection = connectionFields(context)
  const [outcome, setOutcome] = useState<ConnectionOutcome | null>(null)
  const usable = connection.connection !== null || context.mode === 'edit'
  const token = JSON.stringify({ probe: connection.probe })
  const browse = (query: Record<string, unknown>) =>
    browseSource('SHAREPOINT', connection.probeRequest({ sourceUrl: SHAREPOINT_API, query }))

  const run = async (): Promise<ConnectionOutcome> => {
    try {
      const response = await testLibrarySource(
        connection.probeRequest({
          sourceType: 'SHAREPOINT',
          sourceUrl: SHAREPOINT_API,
          sourceSettings: { libraries: [] },
        }),
      )
      return outcomeOf(response, ['auth', 'rights'])
    } catch (err) {
      return outcomeOfError(err, ['auth', 'rights'])
    }
  }

  const load = useCallback(
    async (trail: PickerCrumb[], query?: string): Promise<PickerLevel> => {
      if (trail.length === 0) {
        const text = query?.trim() ?? ''
        const response = await browse(
          /^https:\/\//i.test(text) ? { siteUrl: text } : { search: text },
        )
        return {
          complete: response.complete,
          message: response.message ?? null,
          entries: response.entries.flatMap((entry) => {
            const key = sharePointKeyOf(entry.key)
            if (key?.kind !== 'site') return []
            const name = entry.name ?? key.id
            return [
              {
                key: entry.key,
                name,
                selectable: false,
                hasChildren: true,
                raw: { siteId: key.id, siteName: name },
              },
            ]
          }),
        }
      }
      if (trail.length === 1) {
        const site = trail[0]
        const response = await browse({ site: String(site.raw?.siteId) })
        return {
          complete: response.complete,
          message: response.message ?? null,
          entries: response.entries.flatMap((entry) => {
            const key = sharePointKeyOf(entry.key)
            if (key?.kind !== 'drive') return []
            const name = entry.name ?? key.id
            return [
              {
                key: entry.key,
                name,
                selectable: true,
                hasChildren: true,
                raw: { driveId: key.id, libraryName: sharePointLibraryName(name, site.name) },
              },
            ]
          }),
        }
      }
      const drive = trail[1]
      const below = trail.length > 2 ? trail[trail.length - 1] : null
      const driveId = String(drive.raw?.driveId)
      const response = await browse(
        below ? { drive: driveId, folder: String(below.raw?.folderId) } : { drive: driveId },
      )
      const names = trail.slice(2).map((crumb) => crumb.name)
      return {
        complete: response.complete,
        message: response.message ?? null,
        entries: response.entries.flatMap((entry) => {
          const key = sharePointKeyOf(entry.key)
          if (key?.kind !== 'folder') return []
          const name = entry.name ?? key.id
          return [
            {
              key: entry.key,
              name,
              selectable: true,
              hasChildren: true,
              raw: {
                driveId,
                libraryName: drive.raw?.libraryName ?? null,
                folderId: key.id,
                path: sharePointFolderPath(names, name),
              },
            },
          ]
        }),
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [token],
  )

  const problems = Object.fromEntries(
    (sharePointLibraryChecksOf(outcome?.details) ?? [])
      .filter((check) => !check.reachable && check.message)
      .map((check) => [`drive:${check.driveId}`, String(check.message)]),
  )

  if (!usable) {
    return (
      <Typography sx={{ fontSize: 13.5 }}>
        SharePoint wird über einen Zugang angebunden. Wählen Sie oben einen Zugang.
      </Typography>
    )
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <ConnectionStage
        idPrefix={id}
        token={token}
        stages={['auth', 'rights']}
        run={run}
        summary={[
          connection.connection
            ? `Zugang „${connection.connection.name}“`
            : 'Gespeicherte Anmeldung',
        ]}
        fields={[]}
        autoTest
        onOutcome={setOutcome}
      >
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
          Die Anmeldung läuft über die App-Registrierung des Zugangs.
        </Typography>
      </ConnectionStage>
      {outcome?.ok ? (
        <SplitPicker
          key={token}
          idPrefix={`${id}-libraries`}
          title="Auswahl"
          rootLabel="Sites"
          noun={{ one: 'Eintrag', many: 'Einträge' }}
          levelNoun={(depth) => LEVELS[Math.min(depth, 2)]}
          load={load}
          selected={pickedOfLibraries(values.libraries)}
          onChange={(next) => onChange({ libraries: librariesOfPicked(next) })}
          max={MAX_SHAREPOINT_LIBRARIES}
          emptySelection="Noch keine Dokumentbibliothek gewählt."
          remoteQuery={(trail, text) => trail.length === 0 && /^https:\/\//i.test(text)}
          remoteQueryLabel="Site öffnen"
          millerColumns
          issueFields={['libraries']}
          problems={problems}
        />
      ) : (
        <SelectionPending issueFields={['libraries']} />
      )}
    </Box>
  )
}
