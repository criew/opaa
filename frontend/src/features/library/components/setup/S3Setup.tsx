import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import {
  S3_PROVIDERS,
  S3_PROVIDER_LABELS,
  derivedS3Endpoint,
  isValidS3BucketName,
  normalizeS3Prefix,
  s3CredentialsOf,
  s3ProviderTemplate,
  s3SettingsOf,
  type S3Provider,
  type S3ScopeInput,
  type S3SourceValues,
} from '../../utils/s3Source'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import { connectionFields } from '../sources/sourceConnection'
import type { SourceFormProps } from '../sources/types'
import ConnectionCheck from './ConnectionCheck'
import ScopeLoader from './ScopeLoader'
import SelectionTree, { type TreeListing, type TreeNode, type TreeSelection } from './SelectionTree'
import SetupField, { ScopeField, StoredCredentials } from './SetupField'
import SourceSetupLayout from './SourceSetupLayout'
import { browseWith, listingNote, testRequestOf, tokenOf } from './probe'

const bucketNode = (bucket: string): TreeNode => ({ id: `b:${bucket}`, label: bucket })

/** The scopes of the form as tree selections: a bucket, or a prefix below its parents. */
function scopesToSelection(scopes: S3ScopeInput[]): TreeSelection[] {
  return scopes
    .filter((scope) => scope.bucket.trim() !== '')
    .map((scope) => {
      const bucket = scope.bucket.trim()
      const prefix = normalizeS3Prefix(scope.prefix)
      if (!prefix) return { id: `b:${bucket}`, label: bucket, ancestors: [] }
      const segments = prefix.split('/').filter(Boolean)
      const ancestors: TreeNode[] = [bucketNode(bucket)]
      segments.slice(0, -1).forEach((segment, index) => {
        ancestors.push({
          id: `p:${bucket}/${segments.slice(0, index + 1).join('/')}/`,
          label: segment,
        })
      })
      return { id: `p:${bucket}/${prefix}`, label: segments[segments.length - 1], ancestors }
    })
}

function selectionToScopes(selection: TreeSelection[]): S3ScopeInput[] {
  return selection.map((entry) => {
    if (entry.id.startsWith('b:')) return { bucket: entry.id.slice(2), prefix: '' }
    const rest = entry.id.slice(2)
    const slash = rest.indexOf('/')
    return { bucket: rest.slice(0, slash), prefix: rest.slice(slash + 1) }
  })
}

/**
 * S3 on the shared frame: Anbieter, Endpoint and Region · Schlüssel · buckets and prefixes as a
 * tree, with a bucket by name where the key may not list buckets.
 */
export default function S3Setup({ values, onChange, context }: SourceFormProps<S3SourceValues>) {
  const fields = connectionFields(context)
  const [manual, setManual] = useState({ bucket: '', prefix: '', error: '' })
  const [patternsOpen, setPatternsOpen] = useState(
    values.includePatterns.trim() !== '' || values.excludePatterns.trim() !== '',
  )
  const kept =
    !fields.asksSecret ||
    (context.credentialsStored &&
      sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl))
  const credentials = s3CredentialsOf(values)
  const connectionToken = tokenOf(
    values.sourceUrl.trim(),
    values.region.trim(),
    values.pathStyle,
    credentials ?? null,
  )
  const scopes = values.scopes.filter((scope) => scope.bucket.trim() !== '')
  const base = { region: values.region.trim() || undefined, pathStyle: values.pathStyle }

  const loadChildren = useCallback(
    async (node: TreeNode | null): Promise<TreeListing> => {
      const request = { sourceUrl: values.sourceUrl.trim(), sourceCredentials: credentials }
      if (!node) {
        const response = await browseWith(context, { ...request, query: base })
        return {
          items: response.entries.map((entry) => bucketNode(entry.key)),
          note:
            listingNote(response) ??
            (response.complete ? null : 'Geben Sie den Bucket unten von Hand an.'),
        }
      }
      const rest = node.id.slice(2)
      const bucket = node.id.startsWith('b:') ? rest : rest.slice(0, rest.indexOf('/'))
      const prefix = node.id.startsWith('b:') ? '' : rest.slice(rest.indexOf('/') + 1)
      const response = await browseWith(context, { ...request, query: { ...base, bucket, prefix } })
      return {
        items: response.entries.map((entry) => ({
          id: `p:${bucket}/${entry.key}`,
          label: entry.name ?? entry.key.split('/').filter(Boolean).pop() ?? entry.key,
        })),
        note: listingNote(response),
      }
    },
    // the tree is remounted with every new connection token
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [connectionToken],
  )

  function setScopes(next: S3ScopeInput[]) {
    onChange({ scopes: next.length > 0 ? next : [{ bucket: '', prefix: '' }] })
  }

  function addManual() {
    const bucket = manual.bucket.trim()
    if (!isValidS3BucketName(bucket)) {
      setManual({
        ...manual,
        error:
          'Kein gültiger Bucket-Name: 3–63 Zeichen, Kleinbuchstaben, Ziffern, Punkt, Bindestrich.',
      })
      return
    }
    setScopes([...scopes, { bucket, prefix: normalizeS3Prefix(manual.prefix) }])
    setManual({ bucket: '', prefix: '', error: '' })
  }

  const derives = derivedS3Endpoint(values.provider, values.region) !== null
  return (
    <SourceSetupLayout
      connection={
        <>
          <Box sx={{ maxWidth: 360 }}>
            <FieldLabel id={`${context.idPrefix}-s3-provider-label`}>Anbieter</FieldLabel>
            <Select
              size="small"
              fullWidth
              labelId={`${context.idPrefix}-s3-provider-label`}
              value={values.provider}
              onChange={(event) => onChange(s3ProviderTemplate(event.target.value as S3Provider))}
            >
              {S3_PROVIDERS.map((provider) => (
                <MenuItem key={provider} value={provider}>
                  {S3_PROVIDER_LABELS[provider]}
                </MenuItem>
              ))}
            </Select>
          </Box>
          <SetupField
            field="region"
            label={values.provider === 'HETZNER' ? 'Standort' : 'Region'}
            value={values.region}
            onChange={(region) => {
              const endpoint = derivedS3Endpoint(values.provider, region)
              onChange(endpoint !== null ? { region, sourceUrl: endpoint } : { region })
            }}
            placeholder="eu-central-1"
            width="short"
            mono
          />
          <SetupField
            field="sourceUrl"
            label="Endpoint"
            value={values.sourceUrl}
            onChange={(sourceUrl) => onChange({ sourceUrl })}
            placeholder="https://s3.example.de:9000"
            hint={
              derives
                ? 'Aus der Region abgeleitet.'
                : (fields.addressHint ?? 'https://host[:port], ohne Pfad.')
            }
            mono
          />
          {!derives && (
            <FormControlLabel
              control={
                <Switch
                  checked={values.pathStyle}
                  onChange={(event) => onChange({ pathStyle: event.target.checked })}
                />
              }
              label="Pfad-Stil-Adressen (bei MinIO und Ceph üblich)"
            />
          )}
        </>
      }
      credentials={
        fields.asksSecret ? (
          <StoredCredentials
            stored={kept && context.mode === 'edit'}
            label="Schlüssel"
            onKeep={() => onChange({ accessKey: '', secretKey: '', sessionToken: '' })}
          >
            <SetupField
              field="accessKey"
              label="Schlüssel-ID"
              value={values.accessKey}
              onChange={(accessKey) => onChange({ accessKey })}
              width="medium"
              mono
            />
            <SetupField
              field="secretKey"
              label="Geheimer Schlüssel"
              type="password"
              value={values.secretKey}
              onChange={(secretKey) => onChange({ secretKey })}
              width="medium"
            />
            <SetupField
              field="sessionToken"
              label="Sitzungstoken (optional)"
              type="password"
              value={values.sessionToken}
              onChange={(sessionToken) => onChange({ sessionToken })}
              hint="Nur bei befristeten Schlüsseln."
              width="medium"
            />
          </StoredCredentials>
        ) : undefined
      }
      check={
        <ConnectionCheck
          stages={['address', 'signIn', 'access']}
          token={tokenOf(connectionToken, scopes)}
          missingHint="Bitte zuerst Endpoint und Schlüssel eingeben."
          request={() =>
            values.sourceUrl.trim() && (credentials || kept)
              ? testRequestOf(context, {
                  sourceUrl: values.sourceUrl.trim(),
                  sourceCredentials: credentials,
                  sourceSettings: s3SettingsOf(values),
                })
              : null
          }
        />
      }
      scope={
        <ScopeField field="scopes">
          <ScopeLoader
            loadLabel="Buckets laden"
            hint="Ein Bucket gilt ganz; ein Präfix nur für die Objekte darunter."
            token={connectionToken}
            autoLoad={context.mode === 'edit' && kept}
          >
            {() => (
              <SelectionTree
                label="Buckets"
                noun={{ one: 'Bereich', many: 'Bereiche' }}
                rootLabel="Buckets"
                loadChildren={loadChildren}
                selection={scopesToSelection(scopes)}
                onChange={(next) => setScopes(selectionToScopes(next))}
                openLabel={(node) => `Präfixe von „${node.label}“ anzeigen`}
                emptyText="Der Schlüssel sieht keine Buckets."
              />
            )}
          </ScopeLoader>
          <Box sx={{ mt: 2.5 }}>
            <FieldLabel htmlFor={`${context.idPrefix}-s3-manual-bucket`}>
              Bucket von Hand hinzufügen
            </FieldLabel>
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
              <TextField
                id={`${context.idPrefix}-s3-manual-bucket`}
                size="small"
                value={manual.bucket}
                onChange={(event) =>
                  setManual({ ...manual, bucket: event.target.value, error: '' })
                }
                placeholder="protokolle"
                error={manual.error !== ''}
                slotProps={{ htmlInput: { 'aria-label': 'Name des Buckets' } }}
                sx={{ flex: '1 1 160px', maxWidth: 220 }}
              />
              <TextField
                size="small"
                value={manual.prefix}
                onChange={(event) => setManual({ ...manual, prefix: event.target.value })}
                placeholder="Präfix (optional)"
                slotProps={{ htmlInput: { 'aria-label': 'Präfix (optional)' } }}
                sx={{ flex: '1 1 160px', maxWidth: 220 }}
              />
              <Button variant="text" onClick={addManual}>
                Hinzufügen
              </Button>
            </Box>
            {manual.error && (
              <Typography sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}>
                {manual.error}
              </Typography>
            )}
          </Box>
          <Box sx={{ mt: 2 }}>
            <Button
              variant="text"
              size="small"
              sx={{ ml: -1 }}
              aria-expanded={patternsOpen}
              onClick={() => setPatternsOpen(!patternsOpen)}
            >
              {patternsOpen ? 'Muster ausblenden' : 'Ein- und Ausschlussmuster'}
            </Button>
            {patternsOpen && (
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, mt: 1 }}>
                <SetupField
                  field="includePatterns"
                  label="Einschlussmuster (optional)"
                  value={values.includePatterns}
                  onChange={(includePatterns) => onChange({ includePatterns })}
                  placeholder="**/*.pdf"
                  hint="Ein Muster je Zeile; leer liest alles."
                  multiline
                  mono
                />
                <SetupField
                  field="excludePatterns"
                  label="Ausschlussmuster (optional)"
                  value={values.excludePatterns}
                  onChange={(excludePatterns) => onChange({ excludePatterns })}
                  placeholder="tmp/**"
                  multiline
                  mono
                />
              </Box>
            )}
          </Box>
        </ScopeField>
      }
    />
  )
}
