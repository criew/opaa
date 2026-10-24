import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import {
  S3_PROVIDERS,
  S3_PROVIDER_LABELS,
  s3CredentialsOf,
  s3ProviderTemplate,
  type S3Provider,
  type S3SourceValues,
} from '../../utils/s3Source'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import { connectionFields } from '../sources/sourceConnection'
import { fieldDomId, useSourceIssue } from '../sources/sourceIssueContext'
import type { SourceFormProps } from '../sources/types'
import { FieldGroup, SecretField, SourceTextField } from '../sourceKit/fields'
import SelectionSummary from '../sourceKit/SelectionSummary'
import { s3Item, s3ScopeOfItem, s3SelectionSource } from '../sourceKit/selectionSources'

/**
 * The S3 form (Variante E): Verbindung (provider template, address, region), Anmeldung (access
 * and secret key), Umfang (buckets and prefixes in the shared selection dialog) and the rarely
 * needed options behind „Erweiterte Einstellungen“.
 */
export default function S3Form({ values, onChange, context }: SourceFormProps<S3SourceValues>) {
  const fields = connectionFields(context)
  const issueFor = useSourceIssue()
  const prefix = context.idPrefix
  const advancedIssue =
    issueFor('includePatterns') ?? issueFor('excludePatterns') ?? issueFor('sessionToken')
  const [advanced, setAdvanced] = useState(
    () => values.includePatterns !== '' || values.excludePatterns !== '',
  )
  const open = advanced || advancedIssue !== null
  const stored =
    context.mode === 'edit' &&
    context.credentialsStored &&
    sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)
  const notReady =
    values.sourceUrl.trim() === ''
      ? 'Bitte zuerst die Adresse eingeben.'
      : fields.asksSecret && !stored && (values.accessKey.trim() === '' || values.secretKey === '')
        ? 'Bitte zuerst Kennung und Kennwort eingeben.'
        : null
  const source = s3SelectionSource(fields, () => ({
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: s3CredentialsOf(values),
    query: { region: values.region.trim() || undefined, pathStyle: values.pathStyle },
  }))
  const providerId = fieldDomId(prefix, 'provider')

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <FieldGroup title="Verbindung">
        {!fields.isFixed('sourceUrl') && !fields.connection && (
          <Box>
            <FieldLabel htmlFor={providerId}>Anbieter</FieldLabel>
            <TextField
              id={providerId}
              select
              size="small"
              fullWidth
              value={values.provider}
              onChange={(e) => onChange(s3ProviderTemplate(e.target.value as S3Provider))}
              helperText="Füllt Adresse und Region vor."
            >
              {S3_PROVIDERS.map((provider) => (
                <MenuItem key={provider} value={provider}>
                  {S3_PROVIDER_LABELS[provider]}
                </MenuItem>
              ))}
            </TextField>
          </Box>
        )}
        <SourceTextField
          idPrefix={prefix}
          field="sourceUrl"
          label="Adresse"
          value={values.sourceUrl}
          onChange={(sourceUrl) => onChange({ sourceUrl })}
          placeholder="https://s3.example.org:9000"
          hint={fields.addressHint ?? 'Endpoint des Objektspeichers, ohne Pfad.'}
          mono
          maxLength={2000}
        />
        <SourceTextField
          idPrefix={prefix}
          field="region"
          label="Region"
          optional
          value={values.region}
          onChange={(region) => onChange({ region })}
          placeholder="eu-central-1"
          readOnly={fields.isFixed('region')}
          hint={fields.isFixed('region') ? fields.fixedHint : undefined}
          maxLength={64}
        />
      </FieldGroup>
      {fields.asksSecret && (
        <FieldGroup title="Anmeldung">
          <SourceTextField
            idPrefix={prefix}
            field="accessKey"
            label="Kennung"
            value={values.accessKey}
            onChange={(accessKey) => onChange({ accessKey })}
            hint={
              stored ? 'Access Key; leer lassen, um den gespeicherten zu behalten.' : 'Access Key'
            }
            maxLength={128}
          />
          <SecretField
            idPrefix={prefix}
            field="secretKey"
            label="Kennwort"
            value={values.secretKey}
            stored={stored}
            onChange={(secretKey) => onChange({ secretKey })}
            hint="Secret Key"
            maxLength={128}
          />
        </FieldGroup>
      )}
      <FieldGroup title="Umfang">
        <SelectionSummary
          id={fieldDomId(prefix, 'selection')}
          label="Buckets und Präfixe"
          source={source}
          value={values.scopes.filter((scope) => scope.bucket.trim() !== '').map(s3Item)}
          onChange={(items) => onChange({ scopes: items.map(s3ScopeOfItem) })}
          emptyText="Noch nichts ausgewählt."
          error={issueFor('selection')}
          notReady={notReady}
        />
      </FieldGroup>
      <Box>
        <Button
          onClick={() => setAdvanced((was) => !was)}
          aria-expanded={open}
          aria-controls={`${prefix}-advanced`}
          sx={{ px: 0.5 }}
        >
          {open ? 'Erweiterte Einstellungen ausblenden' : 'Erweiterte Einstellungen'}
        </Button>
        {open && (
          <Box
            id={`${prefix}-advanced`}
            sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 2 }}
          >
            {fields.asksSecret && (
              <SourceTextField
                idPrefix={prefix}
                field="sessionToken"
                label="Session-Token"
                optional
                type="password"
                value={values.sessionToken}
                onChange={(sessionToken) => onChange({ sessionToken })}
                maxLength={242}
              />
            )}
            <FormControlLabel
              control={
                <Switch
                  checked={values.pathStyle}
                  disabled={fields.isFixed('pathStyle')}
                  onChange={(e) => onChange({ pathStyle: e.target.checked })}
                />
              }
              label="Bucket im Pfad adressieren (Path-Style)"
            />
            <SourceTextField
              idPrefix={prefix}
              field="includePatterns"
              label="Einschlussmuster"
              optional
              multiline
              mono
              value={values.includePatterns}
              onChange={(includePatterns) => onChange({ includePatterns })}
              placeholder="**/*.pdf"
              hint="Ein Muster je Zeile."
            />
            <SourceTextField
              idPrefix={prefix}
              field="excludePatterns"
              label="Ausschlussmuster"
              optional
              multiline
              mono
              value={values.excludePatterns}
              onChange={(excludePatterns) => onChange({ excludePatterns })}
              placeholder="archiv/**"
              hint="Ein Muster je Zeile."
            />
          </Box>
        )}
      </Box>
    </Box>
  )
}
