import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import {
  DROPBOX_API,
  isDropboxTeamApp,
  type DropboxSourceValues,
} from '../../../utils/dropboxSource'
import {
  dropboxTeamFolderItem,
  dropboxTeamFolderSource,
  dropboxTeamFoldersOfItems,
} from '../../sourceKit/selectionSources'
import ScopePicker from '../ScopePicker'
import type { SetupFieldsProps } from '../setupTypes'
import FolderScope from './FolderScope'

export function DropboxConnectionFields({
  values,
  onChange,
  context,
  fieldId,
}: SetupFieldsProps<DropboxSourceValues>) {
  if (isDropboxTeamApp(context.connection)) {
    return (
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
        Der Zugang ist eine Team-App: Die Bibliothek liest Team-Ordner des Teams als
        Team-Administration, auch solche, die kein einzelnes Mitgliedskonto sieht.
      </Typography>
    )
  }
  return (
    <Box>
      <FormControlLabel
        control={
          <Switch
            id={fieldId('teamSpace')}
            checked={values.teamSpace}
            onChange={(e) => onChange({ teamSpace: e.target.checked })}
          />
        }
        label="Teambereich lesen"
      />
    </Box>
  )
}

export function DropboxScopeFields({
  values,
  onChange,
  context,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<DropboxSourceValues>) {
  const sourceUrl = fields.connection?.serverUrl ?? DROPBOX_API
  if (isDropboxTeamApp(context.connection)) {
    return (
      <ScopePicker
        id={fieldId('teamFolders')}
        source={dropboxTeamFolderSource(fields, () => ({ sourceUrl }))}
        value={values.teamFolders.map(dropboxTeamFolderItem)}
        onChange={(items) => onChange({ teamFolders: dropboxTeamFoldersOfItems(items) })}
        error={issueAt('teamFolders')}
        loadKey={JSON.stringify({ sourceUrl, probe: fields.probe })}
      />
    )
  }
  return (
    <FolderScope
      id={fieldId('folders')}
      sourceType="DROPBOX"
      folders={values.folders}
      onChange={(folders) => onChange({ folders })}
      fields={fields}
      connection={{ sourceUrl }}
      query={{ teamSpace: values.teamSpace }}
      error={issueAt('folders')}
    />
  )
}
