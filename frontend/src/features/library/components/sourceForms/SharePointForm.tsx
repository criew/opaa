import Typography from '@mui/material/Typography'
import type { SharePointSourceValues } from '../../utils/sharePointSource'
import { connectionFields } from '../sources/sourceConnection'
import { fieldDomId, useSourceIssue } from '../sources/sourceIssueContext'
import type { SourceFormProps } from '../sources/types'
import { FieldGroup } from '../sourceKit/fields'
import SelectionSummary from '../sourceKit/SelectionSummary'
import {
  sharePointItemsOf,
  sharePointLibrariesOfItems,
  sharePointSelectionSource,
} from '../sourceKit/selectionSources'

/**
 * The SharePoint form (Variante E): the Zugang signs in, so only the Umfang remains - document
 * libraries and folders, reached site by site in the shared selection dialog.
 */
export default function SharePointForm({
  values,
  onChange,
  context,
}: SourceFormProps<SharePointSourceValues>) {
  const fields = connectionFields(context)
  const issueFor = useSourceIssue()
  const profile = fields.connection
  return (
    <FieldGroup title="Umfang">
      {profile && (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: -1 }}>
          Anmeldung über den Zugang „{profile.name}“.
        </Typography>
      )}
      <SelectionSummary
        id={fieldDomId(context.idPrefix, 'selection')}
        label="Dokumentbibliotheken und Ordner"
        source={sharePointSelectionSource(fields)}
        value={sharePointItemsOf(values.libraries)}
        onChange={(items) => onChange({ libraries: sharePointLibrariesOfItems(items) })}
        emptyText="Noch nichts ausgewählt."
        error={issueFor('selection')}
      />
    </FieldGroup>
  )
}
