import { useCallback } from 'react'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'
import {
  DROPBOX_API,
  dropboxSettingsFrom,
  type DropboxSourceValues,
} from '../../utils/dropboxSource'
import { connectionFields } from '../sources/sourceConnection'
import type { SourceFormProps } from '../sources/types'
import ConnectionCheck from './ConnectionCheck'
import FolderTreeScope from './FolderTreeScope'
import SourceSetupLayout from './SourceSetupLayout'
import { useSetup } from './setupContext'
import { browseWith, testRequestOf, tokenOf } from './probe'

/**
 * Dropbox on the shared frame: the account is the one the consent was given as, so Verbindung
 * names the Zugang and there is no Zugangsdaten section; Umfang is the team switch and the tree.
 */
export default function DropboxSetup({
  values,
  onChange,
  context,
}: SourceFormProps<DropboxSourceValues>) {
  const fields = connectionFields(context)
  const { connectionLead } = useSetup()
  const serverUrl = fields.connection?.serverUrl ?? DROPBOX_API
  const listingToken = tokenOf(fields.probe, values.teamSpace)
  const browse = useCallback(
    (path: string) =>
      browseWith(context, { sourceUrl: serverUrl, query: { path, teamSpace: values.teamSpace } }),
    // remounted with every new listing token
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [listingToken],
  )
  return (
    <SourceSetupLayout
      connection={
        connectionLead ? undefined : (
          <Typography sx={{ fontSize: 13.5 }}>
            {fields.connection
              ? `Angemeldet über den Zugang „${fields.connection.name}“ mit dem Konto, dem Sie zugestimmt haben.`
              : 'Angemeldet mit dem Konto, dem Sie zugestimmt haben.'}
          </Typography>
        )
      }
      check={
        <ConnectionCheck
          stages={['signIn', 'access']}
          token={tokenOf(listingToken, values.folders)}
          request={() =>
            testRequestOf(context, {
              sourceUrl: serverUrl,
              sourceSettings: dropboxSettingsFrom(values),
            })
          }
        />
      }
      scope={
        <>
          <Box>
            <FormControlLabel
              control={
                <Switch
                  checked={values.teamSpace}
                  onChange={(event) => onChange({ teamSpace: event.target.checked })}
                />
              }
              label="Team-Bereich lesen"
            />
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              Sonst liest OPAA die persönlichen Ordner des Kontos.
            </Typography>
          </Box>
          <FolderTreeScope
            folders={values.folders}
            onChange={(folders) => onChange({ folders })}
            browse={browse}
            token={listingToken}
            autoLoad={context.mode === 'edit'}
          />
        </>
      }
    />
  )
}
