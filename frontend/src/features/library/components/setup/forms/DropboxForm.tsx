import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import { browseSource, testLibrarySource } from '../../../api/libraryApi'
import {
  DROPBOX_API,
  MAX_DROPBOX_FOLDERS,
  dropboxFoldersOf,
  dropboxSettingsFrom,
  type DropboxSourceValues,
} from '../../../utils/dropboxSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceFormProps } from '../../sources/types'
import ConnectionStage from '../ConnectionStage'
import { outcomeOf, outcomeOfError, type ConnectionOutcome } from '../connectionOutcome'
import SplitPicker from '../SplitPicker'
import { foldersOfPicked, levelOf, pickedOfFolders } from '../pathSelection'
import type { PickerCrumb } from '../pickerTypes'
import { SelectionPending } from './AccountFolderForm'

/**
 * The source form of a Dropbox library: the connection is the consent given at Dropbox, so
 * „Verbindung" holds only the team switch; the folders follow in the split selection.
 */
export default function DropboxForm({
  values,
  onChange,
  context,
}: SourceFormProps<DropboxSourceValues>) {
  const id = `${context.idPrefix}-dropbox`
  const connection = connectionFields(context)
  const serverUrl = connection.connection?.serverUrl ?? DROPBOX_API
  const [outcome, setOutcome] = useState<ConnectionOutcome | null>(null)
  const token = JSON.stringify({ probe: connection.probe, teamSpace: values.teamSpace })

  const run = async (): Promise<ConnectionOutcome> => {
    try {
      const response = await testLibrarySource(
        connection.probeRequest({
          sourceType: 'DROPBOX',
          sourceUrl: serverUrl,
          sourceSettings: { ...dropboxSettingsFrom(values), folders: ['/'] },
        }),
      )
      return outcomeOf(response, ['auth', 'rights'])
    } catch (err) {
      return outcomeOfError(err, ['auth', 'rights'])
    }
  }

  const load = useCallback(
    async (trail: PickerCrumb[]) => {
      const path = trail.length ? trail[trail.length - 1].key : '/'
      const response = await browseSource(
        'DROPBOX',
        connection.probeRequest({
          sourceUrl: serverUrl,
          query: { path, teamSpace: values.teamSpace },
        }),
      )
      return levelOf(response, true)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [token],
  )

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <ConnectionStage
        idPrefix={id}
        token={token}
        stages={['auth', 'rights']}
        run={run}
        summary={[
          connection.connection ? `Zugang „${connection.connection.name}“` : 'Dropbox',
          values.teamSpace ? 'Team-Bereich' : 'eigener Bereich',
        ]}
        fields={['teamSpace']}
        autoTest
        onOutcome={setOutcome}
      >
        <Box>
          <FormControlLabel
            control={
              <Switch
                checked={values.teamSpace}
                onChange={(e) => onChange({ teamSpace: e.target.checked })}
              />
            }
            label="Team-Bereich lesen"
          />
          <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
            Liest die Team-Ordner statt des eigenen Bereichs des Kontos.
          </Typography>
        </Box>
      </ConnectionStage>
      {outcome?.ok ? (
        <SplitPicker
          key={token}
          idPrefix={`${id}-folders`}
          title="Auswahl"
          rootLabel="Dropbox"
          noun={{ one: 'Ordner', many: 'Ordner' }}
          load={load}
          selected={pickedOfFolders(dropboxFoldersOf(values))}
          onChange={(next) => onChange({ folders: foldersOfPicked(next) })}
          max={MAX_DROPBOX_FOLDERS}
          emptySelection="Nichts gewählt: Gelesen wird die ganze Dropbox."
          issueFields={['folders']}
        />
      ) : (
        <SelectionPending issueFields={['folders']} />
      )}
    </Box>
  )
}
