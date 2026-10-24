import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import { DROPBOX_API, type DropboxSourceValues } from '../../utils/dropboxSource'
import { connectionFields } from '../sources/sourceConnection'
import { fieldDomId, useSourceIssue } from '../sources/sourceIssueContext'
import type { SourceFormProps } from '../sources/types'
import { FieldGroup } from '../sourceKit/fields'
import SelectionSummary from '../sourceKit/SelectionSummary'
import { folderItemsOf, folderSelectionSource, foldersOfItems } from '../sourceKit/selectionSources'

/**
 * The Dropbox form (Variante E): the account is connected through the Zugang, so only the Umfang
 * remains - the folders, chosen in the shared selection dialog, and whether the team space is read.
 */
export default function DropboxForm({
  values,
  onChange,
  context,
}: SourceFormProps<DropboxSourceValues>) {
  const fields = connectionFields(context)
  const issueFor = useSourceIssue()
  const prefix = context.idPrefix
  const source = folderSelectionSource('DROPBOX', 'Dropbox', fields, () => ({
    sourceUrl: DROPBOX_API,
    query: { teamSpace: values.teamSpace },
  }))
  return (
    <FieldGroup title="Umfang">
      <Box>
        <FormControlLabel
          control={
            <Switch
              id={`${prefix}-team-space`}
              checked={values.teamSpace}
              onChange={(e) => onChange({ teamSpace: e.target.checked, folders: '/' })}
              slotProps={{ input: { 'aria-describedby': `${prefix}-team-space-help` } }}
            />
          }
          label="Team-Bereich lesen"
        />
        <Typography id={`${prefix}-team-space-help`} sx={{ fontSize: 12, color: 'text.secondary' }}>
          Statt der Ordner des Kontos die Ordner seines Dropbox-Teams.
        </Typography>
      </Box>
      <SelectionSummary
        id={fieldDomId(prefix, 'selection')}
        label="Ordner"
        source={source}
        value={folderItemsOf(values.folders)}
        onChange={(items) => onChange({ folders: foldersOfItems(items) })}
        emptyText="Alle Ordner (/)"
        error={issueFor('selection')}
      />
    </FieldGroup>
  )
}
