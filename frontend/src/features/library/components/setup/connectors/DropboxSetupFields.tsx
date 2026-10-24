import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import { DROPBOX_API, type DropboxSourceValues } from '../../../utils/dropboxSource'
import type { SetupFieldsProps } from '../setupTypes'
import FolderScope from './FolderScope'

export function DropboxConnectionFields({
  values,
  onChange,
  fieldId,
}: SetupFieldsProps<DropboxSourceValues>) {
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
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<DropboxSourceValues>) {
  return (
    <FolderScope
      id={fieldId('folders')}
      sourceType="DROPBOX"
      folders={values.folders}
      onChange={(folders) => onChange({ folders })}
      fields={fields}
      connection={{ sourceUrl: fields.connection?.serverUrl ?? DROPBOX_API }}
      query={{ teamSpace: values.teamSpace }}
      error={issueAt('folders')}
    />
  )
}
