import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import {
  S3_PROVIDERS,
  S3_PROVIDER_LABELS,
  derivedS3Endpoint,
  s3CredentialsOf,
  s3ProviderTemplate,
  type S3Provider,
  type S3SourceValues,
} from '../../../utils/s3Source'
import CredentialFields from '../CredentialFields'
import ScopePicker from '../ScopePicker'
import { displayPath } from '../paths'
import SecretField from '../SecretField'
import SetupTextField from '../SetupTextField'
import type { SetupFieldsProps } from '../setupTypes'
import { ALL_SCOPE_HINT, credentialsKept } from './common'
import { browseSource } from '../../../api/libraryApi'
import { s3Item, s3ScopeOfItem, s3SelectionSource } from '../../sourceKit/selectionSources'

export function S3ConnectionFields({
  values,
  onChange,
  context,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<S3SourceValues>) {
  const [advanced, setAdvanced] = useState(false)
  return (
    <Stack spacing={2}>
      <Box sx={{ maxWidth: 320 }}>
        <FieldLabel id={`${fieldId('provider')}-label`}>Anbieter</FieldLabel>
        <Select
          id={fieldId('provider')}
          size="small"
          fullWidth
          labelId={`${fieldId('provider')}-label`}
          value={values.provider}
          onChange={(e) => onChange(s3ProviderTemplate(e.target.value as S3Provider))}
        >
          {S3_PROVIDERS.map((provider) => (
            <MenuItem key={provider} value={provider}>
              {S3_PROVIDER_LABELS[provider]}
            </MenuItem>
          ))}
        </Select>
      </Box>
      <SetupTextField
        id={fieldId('sourceUrl')}
        label="Endpoint"
        value={values.sourceUrl}
        onChange={(sourceUrl) => onChange({ sourceUrl })}
        error={issueAt('sourceUrl')}
        hint={fields.addressHint ?? 'Ohne Bucket und Pfad.'}
        placeholder="https://minio.intern.example:9000"
        mono
      />
      <SetupTextField
        id={fieldId('region')}
        label="Region"
        width="short"
        value={values.region}
        onChange={(region) => {
          const endpoint = derivedS3Endpoint(values.provider, region)
          onChange(endpoint === null ? { region } : { region, sourceUrl: endpoint })
        }}
        error={issueAt('region')}
        placeholder="eu-central-1"
        mono
      />
      {fields.asksSecret && (
        <CredentialFields
          stored={credentialsKept(context, values.sourceUrl)}
          account={{
            id: fieldId('accessKey'),
            label: 'Access Key',
            value: values.accessKey,
            onChange: (accessKey) => onChange({ accessKey }),
            error: issueAt('accessKey'),
          }}
          secret={{
            id: fieldId('secretKey'),
            label: 'Secret Key',
            value: values.secretKey,
            onChange: (secretKey) => onChange({ secretKey }),
            error: issueAt('secretKey'),
          }}
        />
      )}
      <Box>
        <Button
          size="small"
          variant="text"
          aria-expanded={advanced}
          onClick={() => setAdvanced((a) => !a)}
          sx={{ ml: -1 }}
        >
          Erweiterte Einstellungen
        </Button>
      </Box>
      {advanced && (
        <Stack spacing={2}>
          {fields.asksSecret && (
            <SecretField
              id={fieldId('sessionToken')}
              label="Session-Token"
              optional
              value={values.sessionToken}
              onChange={(sessionToken) => onChange({ sessionToken })}
              stored={false}
              error={issueAt('sessionToken')}
              hint="Nur für befristete Schlüssel."
            />
          )}
          <Box>
            <FormControlLabel
              control={
                <Switch
                  checked={values.pathStyle}
                  onChange={(e) => onChange({ pathStyle: e.target.checked })}
                />
              }
              label="Bucket im Pfad adressieren"
            />
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              Für MinIO und Ceph meist an, für AWS aus.
            </Typography>
          </Box>
        </Stack>
      )}
    </Stack>
  )
}

/**
 * Whether the key may list the buckets, as the bucket listing answers for `loadKey`: false for a
 * listing refused as a whole, null while unknown or when the listing failed otherwise.
 */
function useBucketListing(
  loadKey: string,
  list: () => Promise<{ complete: boolean; entries: unknown[] }>,
): boolean | null {
  const [answer, setAnswer] = useState<{ key: string; listable: boolean } | null>(null)
  useEffect(() => {
    let alive = true
    list()
      .then((result) => {
        if (alive) {
          setAnswer({ key: loadKey, listable: result.complete || result.entries.length > 0 })
        }
      })
      .catch(() => alive && setAnswer(null))
    return () => {
      alive = false
    }
    // the key names everything the listing depends on
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadKey])
  return answer?.key === loadKey ? answer.listable : null
}

export function S3ScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<S3SourceValues>) {
  const [advanced, setAdvanced] = useState(
    values.includePatterns.trim() !== '' || values.excludePatterns.trim() !== '',
  )
  const connection = {
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: s3CredentialsOf(values),
  }
  const query = { region: values.region.trim() || undefined, pathStyle: values.pathStyle }
  const loadKey = JSON.stringify({ connection, query, probe: fields.probe })
  const listable = useBucketListing(loadKey, () =>
    browseSource('S3', fields.probeRequest({ ...connection, query })),
  )
  const unlistable = listable === false
  useEffect(() => {
    // „Alle Buckets“ is the preselection only where the key may list the buckets
    if (unlistable && values.allBuckets) onChange({ allBuckets: false })
  }, [unlistable, values.allBuckets, onChange])
  return (
    <Stack spacing={2}>
      <ScopePicker
        id={fieldId('scopes')}
        source={s3SelectionSource(fields, () => ({ ...connection, query }))}
        value={values.scopes.filter((scope) => scope.bucket.trim() !== '').map(s3Item)}
        onChange={(items) => onChange({ scopes: items.map(s3ScopeOfItem) })}
        all={{
          active: values.allBuckets && !unlistable,
          onChange: (allBuckets) => onChange({ allBuckets }),
          label: 'Alle Buckets',
          hint: ALL_SCOPE_HINT,
          unavailable: unlistable
            ? '„Alle Buckets“ setzt voraus, dass der Zugang die Buckets auflisten darf (s3:ListAllMyBuckets).'
            : null,
        }}
        error={issueAt('scopes')}
        labelOf={(item) => displayPath(item.key)}
        loadKey={loadKey}
      />
      <Box>
        <Button
          size="small"
          variant="text"
          aria-expanded={advanced}
          onClick={() => setAdvanced((a) => !a)}
          sx={{ ml: -1 }}
        >
          Ein- und Ausschlussmuster
        </Button>
      </Box>
      {advanced && (
        <Stack spacing={2}>
          <SetupTextField
            id={fieldId('includePatterns')}
            label="Einschlussmuster"
            optional
            multiline
            value={values.includePatterns}
            onChange={(includePatterns) => onChange({ includePatterns })}
            error={issueAt('includePatterns')}
            placeholder="**/*.pdf"
            mono
          />
          <SetupTextField
            id={fieldId('excludePatterns')}
            label="Ausschlussmuster"
            optional
            multiline
            value={values.excludePatterns}
            onChange={(excludePatterns) => onChange({ excludePatterns })}
            error={issueAt('excludePatterns')}
            placeholder="tmp/**"
            mono
          />
        </Stack>
      )}
    </Stack>
  )
}
