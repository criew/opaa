import Box from '@mui/material/Box'
import { connectionFields } from '../sources/sourceConnection'
import { fieldDomId, useSourceIssue } from '../sources/sourceIssueContext'
import type { SourceFormProps } from '../sources/types'
import { FieldGroup, SecretField, SourceTextField, StoredCredentials } from '../sourceKit/fields'
import SelectionSummary from '../sourceKit/SelectionSummary'
import { folderItemsOf, folderSelectionSource, foldersOfItems } from '../sourceKit/selectionSources'

/** The value keys and words that tell a Nextcloud from a Windows share; the form is the same. */
export interface FolderShareShape<V> {
  type: 'NEXTCLOUD' | 'SMB'
  rootLabel: string
  userKey: keyof V & string
  secretKey: keyof V & string
  addressPlaceholder: string
  addressHint: string
  userPlaceholder: string
  secretHint: string
  credentialsOf: (values: V) => string | undefined
  /** Whether stored credentials still serve the entered address. */
  keepsCredentials: (originalUrl: string | null | undefined, url: string) => boolean
}

type FolderValues = { sourceUrl: string; folders: string }

/**
 * The source form of a folder share - Nextcloud or Windows share (Variante E): Verbindung,
 * Anmeldung, Umfang. The folders are chosen in the shared selection dialog; none chosen reads
 * everything („/“).
 */
export default function FolderShareForm<V extends FolderValues>({
  values,
  onChange,
  context,
  shape,
}: SourceFormProps<V> & { shape: FolderShareShape<V> }) {
  const fields = connectionFields(context)
  const issueFor = useSourceIssue()
  const prefix = context.idPrefix
  const stored =
    context.mode === 'edit' &&
    context.credentialsStored &&
    shape.keepsCredentials(context.originalSourceUrl, values.sourceUrl)
  const user = String(values[shape.userKey] ?? '')
  const secret = String(values[shape.secretKey] ?? '')
  const notReady =
    values.sourceUrl.trim() === ''
      ? 'Bitte zuerst die Adresse eingeben.'
      : fields.asksSecret && !stored && (user.trim() === '' || secret === '')
        ? 'Bitte zuerst Kennung und Kennwort eingeben.'
        : null
  const source = folderSelectionSource(shape.type, shape.rootLabel, fields, () => ({
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: shape.credentialsOf(values),
  }))
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <FieldGroup title="Verbindung">
        <SourceTextField
          idPrefix={prefix}
          field="sourceUrl"
          label="Adresse"
          value={values.sourceUrl}
          onChange={(sourceUrl) => onChange({ sourceUrl } as Partial<V>)}
          placeholder={shape.addressPlaceholder}
          hint={fields.addressHint ?? shape.addressHint}
          mono
          maxLength={2000}
        />
      </FieldGroup>
      {fields.asksSecret && (
        <FieldGroup title="Anmeldung">
          <StoredCredentials
            idPrefix={prefix}
            stored={stored}
            entered={user !== '' || secret !== ''}
            onKeep={() => onChange({ [shape.userKey]: '', [shape.secretKey]: '' } as Partial<V>)}
          >
            <SourceTextField
              idPrefix={prefix}
              field={shape.userKey}
              label="Kennung"
              value={user}
              onChange={(next) => onChange({ [shape.userKey]: next } as Partial<V>)}
              placeholder={shape.userPlaceholder}
              maxLength={255}
            />
            <SecretField
              idPrefix={prefix}
              field={shape.secretKey}
              label="Kennwort"
              value={secret}
              stored={false}
              onChange={(next) => onChange({ [shape.secretKey]: next } as Partial<V>)}
              hint={shape.secretHint}
              maxLength={3800}
            />
          </StoredCredentials>
        </FieldGroup>
      )}
      <FieldGroup title="Umfang">
        <SelectionSummary
          id={fieldDomId(prefix, 'selection')}
          label="Ordner"
          source={source}
          value={folderItemsOf(values.folders)}
          onChange={(items) => onChange({ folders: foldersOfItems(items) } as Partial<V>)}
          emptyText="Alle Ordner (/)"
          error={issueFor('selection')}
          notReady={notReady}
        />
      </FieldGroup>
    </Box>
  )
}
