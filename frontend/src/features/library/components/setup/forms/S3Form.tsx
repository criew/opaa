import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { browseSource, testLibrarySource } from '../../../api/libraryApi'
import { sameLibrarySourceOrigin } from '../../../utils/librarySourceConfig'
import {
  MAX_S3_SCOPES,
  S3_PROVIDERS,
  S3_PROVIDER_LABELS,
  derivedS3Endpoint,
  s3CredentialsOf,
  s3ProviderTemplate,
  s3ScopeChecksOf,
  s3SettingsOf,
  type S3Provider,
  type S3ScopeInput,
  type S3SourceValues,
} from '../../../utils/s3Source'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceFormProps } from '../../sources/types'
import ConnectionStage from '../ConnectionStage'
import { outcomeOf, outcomeOfError, type ConnectionOutcome } from '../connectionOutcome'
import SecretField from '../SecretField'
import SourceField from '../SourceField'
import { pickedOfScopes } from '../pickedOf'
import SplitPicker from '../SplitPicker'
import type { PickerCrumb, PickerLevel } from '../pickerTypes'
import { SelectionPending } from './AccountFolderForm'

/**
 * The source form of an S3 library: provider, endpoint, region and key in „Verbindung"; buckets
 * and the prefixes inside them in the split selection. A refused bucket listing leaves the bucket
 * to be typed by hand below the list.
 */
export default function S3Form({ values, onChange, context }: SourceFormProps<S3SourceValues>) {
  const id = `${context.idPrefix}-s3`
  const connection = connectionFields(context)
  const [outcome, setOutcome] = useState<ConnectionOutcome | null>(null)
  const [advanced, setAdvanced] = useState(false)
  const stored =
    context.mode === 'edit' &&
    context.credentialsStored &&
    sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)
  const token = JSON.stringify({
    probe: connection.probe,
    url: values.sourceUrl,
    region: values.region,
    pathStyle: values.pathStyle,
    accessKey: values.accessKey,
    secretKey: values.secretKey,
    sessionToken: values.sessionToken,
  })
  const payload = () => ({
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: s3CredentialsOf(values),
  })

  const run = async (): Promise<ConnectionOutcome> => {
    try {
      const response = await testLibrarySource(
        connection.probeRequest({
          sourceType: 'S3',
          ...payload(),
          sourceSettings: { ...s3SettingsOf(values), scopes: [] },
        }),
      )
      return outcomeOf(
        { ...response, reachable: response.reachable || response.credentialsVerified === true },
        ['address', 'auth', 'rights'],
      )
    } catch (err) {
      return outcomeOfError(err, ['address', 'auth', 'rights'])
    }
  }

  const load = useCallback(
    async (trail: PickerCrumb[]): Promise<PickerLevel> => {
      const options = { region: values.region.trim() || undefined, pathStyle: values.pathStyle }
      if (trail.length === 0) {
        const response = await browseSource(
          'S3',
          connection.probeRequest({ ...payload(), query: options }),
        )
        return {
          complete: response.complete,
          message: response.message ?? null,
          entries: response.entries.map((entry) => ({
            key: entry.key,
            name: entry.key,
            selectable: true,
            hasChildren: true,
            raw: { bucket: entry.key, prefix: '' },
          })),
        }
      }
      const bucket = String(trail[0].raw?.bucket ?? trail[0].key)
      const prefix = trail.length > 1 ? String(trail[trail.length - 1].raw?.prefix ?? '') : ''
      const response = await browseSource(
        'S3',
        connection.probeRequest({ ...payload(), query: { ...options, bucket, prefix } }),
      )
      return {
        complete: response.complete,
        message: response.message ?? null,
        entries: response.entries.map((entry) => ({
          key: `${bucket}/${entry.key}`,
          name: entry.name ?? entry.key,
          selectable: true,
          hasChildren: true,
          raw: { bucket, prefix: entry.key },
        })),
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [token],
  )

  const problems = Object.fromEntries(
    (s3ScopeChecksOf(outcome?.details) ?? [])
      .filter((check) => check.message)
      .map((check) => [
        check.prefix ? `${check.bucket}/${check.prefix}` : check.bucket,
        String(check.message),
      ]),
  )

  const complete =
    values.sourceUrl.trim() !== '' &&
    (values.accessKey.trim() !== '' || stored || !connection.asksSecret)
  const endpointDerived = derivedS3Endpoint(values.provider, values.region) !== null

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <ConnectionStage
        idPrefix={id}
        token={token}
        stages={['address', 'auth', 'rights']}
        run={run}
        summary={[
          S3_PROVIDER_LABELS[values.provider],
          values.sourceUrl.trim(),
          values.region.trim(),
          values.accessKey.trim() || (stored ? 'gespeicherter Schlüssel' : ''),
        ]}
        fields={['sourceUrl', 'region', 'accessKey', 'secretKey', 'sessionToken']}
        autoTest={
          complete && (context.mode === 'edit' || values.secretKey !== '' || !connection.asksSecret)
        }
        onOutcome={setOutcome}
      >
        <Box sx={{ maxWidth: '34ch' }}>
          <FieldLabel htmlFor={`${id}-provider`}>Anbieter</FieldLabel>
          <TextField
            id={`${id}-provider`}
            select
            size="small"
            fullWidth
            value={values.provider}
            onChange={(e) => onChange(s3ProviderTemplate(e.target.value as S3Provider))}
          >
            {S3_PROVIDERS.map((provider) => (
              <MenuItem key={provider} value={provider}>
                {S3_PROVIDER_LABELS[provider]}
              </MenuItem>
            ))}
          </TextField>
        </Box>
        <SourceField
          id={`${id}-region`}
          field="region"
          label="Region"
          value={values.region}
          onChange={(next) => {
            const derived = derivedS3Endpoint(values.provider, next)
            onChange(derived !== null ? { region: next, sourceUrl: derived } : { region: next })
          }}
          placeholder="eu-central-1"
          width={20}
          mono
        />
        <SourceField
          id={`${id}-url`}
          field="sourceUrl"
          label="Endpoint"
          value={values.sourceUrl}
          onChange={(next) => onChange({ sourceUrl: next })}
          placeholder="https://s3.example.org:9000"
          hint={endpointDerived ? 'Folgt aus der Region.' : (connection.addressHint ?? undefined)}
          mono
        />
        {connection.asksSecret && (
          <>
            <SourceField
              id={`${id}-access`}
              field="accessKey"
              label="Access Key"
              value={values.accessKey}
              onChange={(next) => onChange({ accessKey: next })}
              hint={
                stored && values.accessKey === ''
                  ? 'Leer lassen, um den gespeicherten Schlüssel zu behalten.'
                  : undefined
              }
              mono
            />
            <SecretField
              id={`${id}-secret`}
              field="secretKey"
              label="Secret Key"
              value={values.secretKey}
              onChange={(next) => onChange({ secretKey: next })}
              stored={stored}
            />
          </>
        )}
        <Box>
          <Button
            size="small"
            aria-expanded={advanced}
            aria-controls={`${id}-advanced`}
            onClick={() => setAdvanced((open) => !open)}
          >
            {advanced ? 'Weniger Einstellungen' : 'Weitere Einstellungen'}
          </Button>
          {advanced && (
            <Box
              id={`${id}-advanced`}
              sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}
            >
              {connection.asksSecret && (
                <SourceField
                  id={`${id}-session`}
                  field="sessionToken"
                  label="Session-Token"
                  optional
                  type="password"
                  value={values.sessionToken}
                  onChange={(next) => onChange({ sessionToken: next })}
                  hint="Nur für befristete Schlüssel."
                />
              )}
              <FormControlLabel
                control={
                  <Switch
                    checked={values.pathStyle}
                    onChange={(e) => onChange({ pathStyle: e.target.checked })}
                  />
                }
                label="Pfad-Stil-Adressen"
              />
            </Box>
          )}
        </Box>
      </ConnectionStage>

      {outcome?.ok ? (
        <SplitPicker
          key={token}
          idPrefix={`${id}-scopes`}
          title="Auswahl"
          rootLabel="Buckets"
          noun={{ one: 'Bereich', many: 'Bereiche' }}
          levelNoun={(depth) =>
            depth === 0 ? { one: 'Bucket', many: 'Buckets' } : { one: 'Präfix', many: 'Präfixe' }
          }
          load={load}
          selected={pickedOfScopes(values.scopes)}
          onChange={(next) =>
            onChange({
              scopes: next.map((item) => ({
                bucket: String(item.raw?.bucket ?? item.key),
                prefix: String(item.raw?.prefix ?? ''),
              })),
            })
          }
          max={MAX_S3_SCOPES}
          emptySelection="Noch kein Bucket gewählt."
          issueFields={['scopes']}
          problems={problems}
          manualEntry={
            <ManualScope
              idPrefix={id}
              onAdd={(scope) =>
                onChange({ scopes: [...values.scopes.filter((s) => s.bucket.trim()), scope] })
              }
            />
          }
        />
      ) : (
        <SelectionPending issueFields={['scopes']} />
      )}

      <Box>
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.5 }}>
          Muster
        </Typography>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <SourceField
            id={`${id}-include`}
            field="includePatterns"
            label="Nur diese Dateien"
            optional
            multiline
            mono
            value={values.includePatterns}
            onChange={(next) => onChange({ includePatterns: next })}
            placeholder="**/*.pdf"
          />
          <SourceField
            id={`${id}-exclude`}
            field="excludePatterns"
            label="Ausschlussmuster"
            optional
            multiline
            mono
            value={values.excludePatterns}
            onChange={(next) => onChange({ excludePatterns: next })}
            placeholder="archiv/**"
          />
        </Box>
      </Box>
    </Box>
  )
}

/** A bucket (and prefix) typed by hand, for a key that may not list buckets. */
function ManualScope({
  idPrefix,
  onAdd,
}: {
  idPrefix: string
  onAdd: (scope: S3ScopeInput) => void
}) {
  const [bucket, setBucket] = useState('')
  const [prefix, setPrefix] = useState('')
  const add = () => {
    if (bucket.trim() === '') return
    onAdd({ bucket: bucket.trim(), prefix })
    setBucket('')
    setPrefix('')
  }
  return (
    <Box
      component="form"
      onSubmit={(e) => {
        e.preventDefault()
        add()
      }}
      sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, alignItems: 'flex-end', mt: 1 }}
    >
      <Box sx={{ flex: '1 1 10rem' }}>
        <FieldLabel htmlFor={`${idPrefix}-manual-bucket`}>Bucket von Hand</FieldLabel>
        <TextField
          id={`${idPrefix}-manual-bucket`}
          size="small"
          fullWidth
          value={bucket}
          onChange={(e) => setBucket(e.target.value)}
          placeholder="protokolle"
        />
      </Box>
      <Box sx={{ flex: '1 1 10rem' }}>
        <FieldLabel htmlFor={`${idPrefix}-manual-prefix`}>Präfix (optional)</FieldLabel>
        <TextField
          id={`${idPrefix}-manual-prefix`}
          size="small"
          fullWidth
          value={prefix}
          onChange={(e) => setPrefix(e.target.value)}
          placeholder="2024/"
        />
      </Box>
      <Button type="submit" variant="outlined">
        Hinzufügen
      </Button>
    </Box>
  )
}
