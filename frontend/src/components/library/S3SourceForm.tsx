import { useRef, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { S3ScopeCheck, SourceConnectionTestResponse } from '../../types/api'
import { listS3Buckets, testLibrarySource } from '../../services/api'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import FieldLabel from '../wizard/FieldLabel'

import {
  AWS_REGIONS,
  derivedS3Endpoint,
  HETZNER_LOCATIONS,
  MAX_S3_ACCESS_KEY_LENGTH,
  MAX_S3_SCOPES,
  MAX_S3_SECRET_KEY_LENGTH,
  MAX_S3_SESSION_TOKEN_LENGTH,
  S3_PROVIDER_LABELS,
  S3_PROVIDERS,
  s3CredentialsOf,
  s3ProviderTemplate,
  s3ScopeKey,
  s3SettingsOf,
  validateS3Prefix,
  validateS3Scope,
  type S3Provider,
  type S3ScopeInput,
  type S3SourceValues,
} from '../../utils/s3Source'

interface Message {
  severity: 'success' | 'warning' | 'error' | 'info'
  text: string
}

interface S3SourceFormProps {
  mode: 'create' | 'edit'
  values: S3SourceValues
  onChange: (patch: Partial<S3SourceValues>) => void
  /** Edit mode: lets the test and the bucket listing fall back to the stored key. */
  libraryId?: string
  /** Edit mode: whether a key is stored for this library. */
  credentialsStored?: boolean
  /** Edit mode: the endpoint the stored key belongs to - it does not survive a host change. */
  originalSourceUrl?: string | null
  idPrefix: string
}

function scopeCheckSummary(check: S3ScopeCheck): string {
  const name = check.prefix ? `${check.bucket}/${check.prefix}` : check.bucket
  if (check.message) {
    return `${name}: ${check.message}`
  }
  const reading =
    check.readAllowed === null || check.readAllowed === undefined
      ? 'Lesen nicht geprüft (kein Objekt)'
      : 'Lesen ✓'
  const count = `${check.objectCount} Objekt${check.objectCount === 1 ? '' : 'e'}${check.objectCountIsLowerBound ? ' (mindestens)' : ''}`
  return `${name}: erreichbar · Listen ✓ · ${reading} · ${count}`
}

/**
 * The staged S3 form (ADR-0027): a provider template first - it prefills endpoint, region and
 * addressing style, every prefill stays editable -, then the static key, then the one sentence the
 * scope selection must not be made without (everything indexed from every scope is readable by
 * everyone who may read this library), then the scopes with an optional bucket suggestion from the
 * store, the key patterns and the connection test that reports every scope on its own.
 *
 * <p>Every answer from the store is checked against a generation counter that each edit of the
 * connection or the key advances: a late answer for a since-changed input is dropped.
 */
export default function S3SourceForm({
  mode,
  values,
  onChange,
  libraryId,
  credentialsStored = false,
  originalSourceUrl,
  idPrefix,
}: S3SourceFormProps) {
  const [testing, setTesting] = useState(false)
  const [testMessage, setTestMessage] = useState<Message | null>(null)
  const [scopeChecks, setScopeChecks] = useState<S3ScopeCheck[] | null>(null)
  const [loadingBuckets, setLoadingBuckets] = useState(false)
  const [bucketOptions, setBucketOptions] = useState<string[]>([])
  const [bucketsMessage, setBucketsMessage] = useState<Message | null>(null)
  const [advancedOpen, setAdvancedOpen] = useState(
    () =>
      values.includePatterns !== '' || values.excludePatterns !== '' || values.sourceProxy !== '',
  )
  // two counters: the listing only depends on connection and key, so editing a scope or a
  // pattern must not discard a bucket listing that is still in flight
  const generation = useRef(0)
  const listingGeneration = useRef(0)
  const removeButtons = useRef<Array<HTMLButtonElement | null>>([])
  const addScopeButton = useRef<HTMLButtonElement | null>(null)

  // #542 review finding 1, mirrored for S3: the backend carries the stored key forward only while
  // the endpoint still names the same origin; a host change drops it.
  const originChanged =
    mode === 'edit' &&
    credentialsStored &&
    values.sourceUrl.trim() !== '' &&
    !sameLibrarySourceOrigin(originalSourceUrl, values.sourceUrl)
  const keyEntered = values.accessKey.trim() !== '' || values.secretKey.trim() !== ''
  const usesStoredCredentials =
    mode === 'edit' && credentialsStored && !originChanged && !keyEntered && libraryId !== undefined
  const keyComplete =
    (values.accessKey.trim() !== '' && values.secretKey.trim() !== '') || usesStoredCredentials
  const endpointEntered = values.sourceUrl.trim() !== ''
  const scopeEntered = values.scopes.some((scope) => scope.bucket.trim() !== '')
  const regionSuggestions =
    values.provider === 'AWS' ? AWS_REGIONS : values.provider === 'HETZNER' ? HETZNER_LOCATIONS : []

  function invalidate() {
    generation.current += 1
    setTestMessage(null)
    setScopeChecks(null)
    setTesting(false)
  }

  function invalidateListing() {
    listingGeneration.current += 1
    setBucketOptions([])
    setBucketsMessage(null)
    setLoadingBuckets(false)
  }

  function changeConnection(patch: Partial<S3SourceValues>) {
    invalidate()
    invalidateListing()
    onChange(patch)
  }

  function changeKey(patch: Partial<S3SourceValues>) {
    invalidate()
    invalidateListing()
    onChange(patch)
  }

  function changeProvider(provider: S3Provider) {
    changeConnection(s3ProviderTemplate(provider))
  }

  function changeRegion(region: string) {
    const derived = derivedS3Endpoint(values.provider, region)
    changeConnection(derived === null ? { region } : { region, sourceUrl: derived })
  }

  function changeScopes(scopes: S3ScopeInput[]) {
    invalidate()
    onChange({ scopes })
  }

  function changePatterns(patch: Partial<S3SourceValues>) {
    invalidate()
    onChange(patch)
  }

  function updateScope(index: number, patch: Partial<S3ScopeInput>) {
    changeScopes(values.scopes.map((scope, i) => (i === index ? { ...scope, ...patch } : scope)))
  }

  function removeScope(index: number) {
    changeScopes(values.scopes.filter((_s, i) => i !== index))
    // the button that had focus disappears with its row: move to the previous row's button, or
    // to "Bereich" when only one row remains (its button is disabled after the render)
    const previous = removeButtons.current[index - 1]
    ;(values.scopes.length > 2 && previous ? previous : addScopeButton.current)?.focus()
  }

  function connectionPayload() {
    return {
      sourceUrl: values.sourceUrl.trim(),
      sourceProxy: values.sourceProxy.trim() || undefined,
      sourceInsecureSsl: values.sourceInsecureSsl,
      sourceCredentials: s3CredentialsOf(values),
      libraryId: usesStoredCredentials ? libraryId : undefined,
    }
  }

  async function loadBuckets() {
    const mine = listingGeneration.current
    setLoadingBuckets(true)
    setBucketsMessage(null)
    try {
      const result = await listS3Buckets({
        ...connectionPayload(),
        region: values.region.trim() || undefined,
        pathStyle: values.pathStyle,
      })
      if (listingGeneration.current !== mine) return
      if (result.listingPermitted) {
        setBucketOptions(result.buckets)
        setBucketsMessage({
          severity: 'success',
          text:
            result.buckets.length === 0
              ? 'Der Schlüssel sieht keine Buckets - der Bucket-Name kann von Hand eingetragen werden.'
              : `${result.buckets.length} Bucket${result.buckets.length === 1 ? '' : 's'} geladen; sie stehen in den Bucket-Feldern zur Auswahl.`,
        })
      } else {
        setBucketOptions([])
        setBucketsMessage({
          severity: 'info',
          text:
            result.message ??
            'Die Bucket-Liste ist mit diesen Zugangsdaten nicht lesbar - der Bucket-Name kann von Hand eingetragen werden.',
        })
      }
    } catch (err) {
      if (listingGeneration.current !== mine) return
      setBucketOptions([])
      setBucketsMessage({
        severity: 'error',
        text: err instanceof Error ? err.message : 'Die Buckets konnten nicht geladen werden',
      })
    } finally {
      if (listingGeneration.current === mine) setLoadingBuckets(false)
    }
  }

  async function testConnection() {
    const mine = generation.current
    setTesting(true)
    setTestMessage(null)
    setScopeChecks(null)
    try {
      const result: SourceConnectionTestResponse = await testLibrarySource({
        sourceType: 'S3',
        ...connectionPayload(),
        s3Settings: s3SettingsOf(values),
      })
      if (generation.current !== mine) return
      setTestMessage({
        severity: result.reachable ? 'success' : 'warning',
        text: result.message,
      })
      setScopeChecks(result.s3Scopes ?? null)
    } catch (err) {
      if (generation.current !== mine) return
      setTestMessage({
        severity: 'error',
        text: err instanceof Error ? err.message : 'Verbindung konnte nicht getestet werden',
      })
    } finally {
      if (generation.current === mine) setTesting(false)
    }
  }

  const secretHelperText = originChanged
    ? 'Der Endpoint zeigt auf einen anderen Server — der hinterlegte Schlüssel gilt dort nicht und muss neu eingegeben werden.'
    : mode === 'edit' && credentialsStored
      ? 'Leer lassen, um den hinterlegten Schlüssel beizubehalten. Wird nie in einer API-Antwort ausgegeben.'
      : 'Wird nie in einer API-Antwort ausgegeben.'

  return (
    <Stack spacing={2.5}>
      {/* Stage 1: provider template and connection */}
      <Box>
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
          Verbindung zum Objektspeicher
        </Typography>
        <FieldLabel id={`${idPrefix}-provider-label`}>Anbieter</FieldLabel>
        <RadioGroup
          aria-labelledby={`${idPrefix}-provider-label`}
          row
          value={values.provider}
          onChange={(e) => changeProvider(e.target.value as S3Provider)}
          sx={{ mb: 1 }}
        >
          {S3_PROVIDERS.map((provider) => (
            <FormControlLabel
              key={provider}
              value={provider}
              control={<Radio size="small" />}
              label={S3_PROVIDER_LABELS[provider]}
            />
          ))}
        </RadioGroup>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>
          Die Vorlage belegt Endpoint, Region und Adressstil vor; jede Vorbelegung bleibt änderbar.
        </Typography>
        {values.provider === 'HETZNER' && (
          <Alert severity="info" sx={{ mb: 1.5 }}>
            Hetzner Object Storage bietet keine Ereignisbenachrichtigungen (eine Aktualisierung per
            Push ist dort nicht möglich) und Verschlüsselung nur als SSE-C.
          </Alert>
        )}
        <Box
          sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: '14px' }}
        >
          <Box>
            <FieldLabel htmlFor={`${idPrefix}-region`}>
              {values.provider === 'HETZNER' ? 'Standort (Region)' : 'Region'}
            </FieldLabel>
            <Autocomplete<string, false, false, true>
              id={`${idPrefix}-region`}
              freeSolo
              options={regionSuggestions}
              value={values.region}
              inputValue={values.region}
              onInputChange={(_e, region) => changeRegion(region)}
              renderInput={(params) => (
                <TextField
                  {...params}
                  size="small"
                  placeholder={values.provider === 'AWS' ? 'eu-central-1' : 'us-east-1'}
                  helperText={
                    values.provider === 'AWS' || values.provider === 'HETZNER'
                      ? 'Der Endpoint wird aus der Region abgeleitet.'
                      : 'Wird zum Signieren verwendet; MinIO und Ceph akzeptieren us-east-1.'
                  }
                />
              )}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor={`${idPrefix}-url`}>Endpoint</FieldLabel>
            <TextField
              id={`${idPrefix}-url`}
              size="small"
              value={values.sourceUrl}
              onChange={(e) => changeConnection({ sourceUrl: e.target.value })}
              placeholder="https://minio.intern.example:9000"
              helperText="http(s)://host[:port], ohne Pfad - Bucket und Präfix gehören in die Geltungsbereiche."
              fullWidth
              slotProps={{ htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } } }}
            />
          </Box>
          <FormControlLabel
            sx={{ gridColumn: '1 / -1' }}
            control={
              <Switch
                checked={values.pathStyle}
                onChange={(e) => changeConnection({ pathStyle: e.target.checked })}
              />
            }
            label="Path-Style-Adressierung (endpoint/bucket/key - MinIO, Ceph)"
          />
        </Box>
      </Box>

      {/* Stage 2: the static key */}
      <Box>
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
          Zugangsdaten
        </Typography>
        <Box
          sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: '14px' }}
        >
          <Box>
            <FieldLabel htmlFor={`${idPrefix}-access-key`}>
              {mode === 'edit' && credentialsStored ? 'Neuer Access Key' : 'Access Key'}
            </FieldLabel>
            <TextField
              id={`${idPrefix}-access-key`}
              size="small"
              value={values.accessKey}
              onChange={(e) => changeKey({ accessKey: e.target.value })}
              placeholder="AKIA…"
              autoComplete="off"
              error={originChanged && values.accessKey.trim() === ''}
              helperText="Ohne Doppelpunkt."
              fullWidth
              slotProps={{
                htmlInput: {
                  maxLength: MAX_S3_ACCESS_KEY_LENGTH,
                  sx: { fontFamily: 'monospace' },
                },
              }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor={`${idPrefix}-secret-key`}>
              {mode === 'edit' && credentialsStored ? 'Neuer Secret Key' : 'Secret Key'}
            </FieldLabel>
            <TextField
              id={`${idPrefix}-secret-key`}
              size="small"
              type="password"
              value={values.secretKey}
              onChange={(e) => changeKey({ secretKey: e.target.value })}
              autoComplete="new-password"
              error={originChanged && values.secretKey.trim() === ''}
              helperText={secretHelperText}
              fullWidth
              slotProps={{ htmlInput: { maxLength: MAX_S3_SECRET_KEY_LENGTH } }}
            />
          </Box>
          <Box sx={{ gridColumn: '1 / -1' }}>
            <FieldLabel htmlFor={`${idPrefix}-session-token`}>Session-Token (optional)</FieldLabel>
            <TextField
              id={`${idPrefix}-session-token`}
              size="small"
              type="password"
              value={values.sessionToken}
              onChange={(e) => changeKey({ sessionToken: e.target.value })}
              autoComplete="off"
              helperText="Nur für zeitlich begrenzte Schlüssel (STS); läuft mit dem Token ab. Access Key, Secret Key und Token dürfen zusammen höchstens 500 Zeichen umfassen."
              fullWidth
              slotProps={{ htmlInput: { maxLength: MAX_S3_SESSION_TOKEN_LENGTH } }}
            />
          </Box>
        </Box>
      </Box>

      {/* Stage 3: the consequence, stated before the scopes - not after them */}
      <Alert severity="warning" data-testid={`${idPrefix}-sharing-consequence`}>
        <strong>Wer diese Bibliothek lesen darf, sieht alles aus allen Geltungsbereichen.</strong>{' '}
        Die Berechtigungen im Objektspeicher enden mit der Indizierung; OPAA übernimmt sie nicht.
        Sollen Personen einen Bucket oder ein Präfix lesen dürfen, ein anderes aber nicht, legen Sie
        dafür eine zweite Bibliothek mit eigenen Geltungsbereichen an.
      </Alert>

      {/* Stage 4: scopes */}
      <Box aria-busy={loadingBuckets}>
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 0.5 }}>
          Geltungsbereiche
        </Typography>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 1.5 }}>
          Je Zeile ein Bucket mit optionalem Präfix („2025/protokolle“). Zwei Bereiche derselben
          Bibliothek dürfen sich nicht überschneiden.
        </Typography>
        <List disablePadding aria-label="Geltungsbereiche">
          {values.scopes.map((scope, index) => {
            const bucketError =
              scope.bucket.trim() === ''
                ? null
                : validateS3Scope({ bucket: scope.bucket, prefix: '' })
            const prefixError = scope.prefix.trim() === '' ? null : validateS3Prefix(scope.prefix)
            return (
              <ListItem
                key={index}
                disableGutters
                sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 1.5, py: 0.75 }}
              >
                <Box>
                  <FieldLabel htmlFor={`${idPrefix}-scope-${index}-bucket`}>
                    Bucket {index + 1}
                  </FieldLabel>
                  <Autocomplete<string, false, false, true>
                    id={`${idPrefix}-scope-${index}-bucket`}
                    freeSolo
                    options={bucketOptions}
                    value={scope.bucket}
                    inputValue={scope.bucket}
                    onInputChange={(_e, bucket) => updateScope(index, { bucket })}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        size="small"
                        placeholder="dokumente"
                        error={bucketError !== null}
                        helperText={bucketError ?? undefined}
                      />
                    )}
                  />
                </Box>
                <Box>
                  <FieldLabel htmlFor={`${idPrefix}-scope-${index}-prefix`}>
                    Präfix {index + 1} (optional)
                  </FieldLabel>
                  <TextField
                    id={`${idPrefix}-scope-${index}-prefix`}
                    size="small"
                    value={scope.prefix}
                    onChange={(e) => updateScope(index, { prefix: e.target.value })}
                    placeholder="2025/protokolle/"
                    error={prefixError !== null}
                    helperText={prefixError ?? undefined}
                    fullWidth
                    slotProps={{ htmlInput: { maxLength: 1023, sx: { fontFamily: 'monospace' } } }}
                  />
                </Box>
                <IconButton
                  ref={(node) => {
                    removeButtons.current[index] = node
                  }}
                  size="small"
                  aria-label={`Bereich ${index + 1} entfernen`}
                  disabled={values.scopes.length === 1}
                  onClick={() => removeScope(index)}
                  sx={{ alignSelf: 'end', mb: bucketError || prefixError ? 3 : 0.5 }}
                >
                  <DeleteIcon sx={{ fontSize: 18 }} />
                </IconButton>
              </ListItem>
            )
          })}
        </List>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
          <Button
            ref={addScopeButton}
            size="small"
            variant="outlined"
            startIcon={<AddIcon />}
            disabled={values.scopes.length >= MAX_S3_SCOPES}
            onClick={() => changeScopes([...values.scopes, { bucket: '', prefix: '' }])}
          >
            Bereich
          </Button>
          <Button
            size="small"
            variant="outlined"
            disabled={loadingBuckets || !endpointEntered || !keyComplete}
            onClick={() => void loadBuckets()}
          >
            {loadingBuckets ? 'Buckets werden geladen …' : 'Buckets laden'}
          </Button>
        </Stack>
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.75 }}>
          {`${values.scopes.length} von höchstens ${MAX_S3_SCOPES} Bereichen.`}
          {mode === 'edit' &&
            ' Ein entfernter Bereich verschwindet mit dem nächsten Lauf aus dem Index; jede Änderung der Bereiche verwirft den Wiederaufnahmestand.'}
        </Typography>
        {bucketsMessage && (
          <Alert severity={bucketsMessage.severity} sx={{ mt: 1 }}>
            {bucketsMessage.text}
          </Alert>
        )}
      </Box>

      {/* Stage 5: advanced - key patterns, proxy, TLS */}
      <Accordion
        variant="outlined"
        disableGutters
        expanded={advancedOpen}
        onChange={(_e, open) => setAdvancedOpen(open)}
      >
        <AccordionSummary expandIcon={<ExpandMoreIcon />} id={`${idPrefix}-advanced`}>
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
            Erweitert: Ein-/Ausschlussmuster, Proxy, TLS
          </Typography>
        </AccordionSummary>
        <AccordionDetails>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
              gap: '14px',
            }}
          >
            <Box>
              <FieldLabel htmlFor={`${idPrefix}-include`}>Einschlussmuster (optional)</FieldLabel>
              <TextField
                id={`${idPrefix}-include`}
                size="small"
                multiline
                minRows={2}
                value={values.includePatterns}
                onChange={(e) => changePatterns({ includePatterns: e.target.value })}
                placeholder={'**/*.pdf\n**/*.docx'}
                helperText="Glob-Muster auf den vollständigen Objektschlüssel (mit Bereichspräfix), eines je Zeile; leer = alle Schlüssel. „*“ bleibt innerhalb eines Segments, „**“ überschreitet Schrägstriche: „**/*.pdf“ trifft PDFs in Unterordnern, „*.pdf“ nur auf der obersten Ebene."
                fullWidth
                slotProps={{ htmlInput: { sx: { fontFamily: 'monospace' } } }}
              />
            </Box>
            <Box>
              <FieldLabel htmlFor={`${idPrefix}-exclude`}>Ausschlussmuster (optional)</FieldLabel>
              <TextField
                id={`${idPrefix}-exclude`}
                size="small"
                multiline
                minRows={2}
                value={values.excludePatterns}
                onChange={(e) => changePatterns({ excludePatterns: e.target.value })}
                placeholder={'**/~*\n**/.DS_Store'}
                helperText="Ein passender Schlüssel wird übersprungen - nach den Einschlussmustern. Was die Muster ausschließen, gehört nicht zum Bestand: bereits aufgenommene Objekte werden mit dem nächsten Vollabgleich entfernt."
                fullWidth
                slotProps={{ htmlInput: { sx: { fontFamily: 'monospace' } } }}
              />
            </Box>
            <Box>
              <FieldLabel htmlFor={`${idPrefix}-proxy`}>Proxy (optional)</FieldLabel>
              <TextField
                id={`${idPrefix}-proxy`}
                size="small"
                value={values.sourceProxy}
                onChange={(e) => changeConnection({ sourceProxy: e.target.value })}
                placeholder="proxy.example.com:8080"
                autoComplete="off"
                fullWidth
                slotProps={{ htmlInput: { maxLength: 255 } }}
              />
            </Box>
            <FormControlLabel
              sx={{ alignSelf: 'end' }}
              control={
                <Switch
                  checked={values.sourceInsecureSsl}
                  onChange={(e) => changeConnection({ sourceInsecureSsl: e.target.checked })}
                />
              }
              label="TLS-Prüfung aussetzen"
            />
            {values.sourceInsecureSsl && (
              <Alert severity="warning" sx={{ gridColumn: '1 / -1' }}>
                Ohne Zertifikatsprüfung kann sich jeder Server als der Objektspeicher ausgeben und
                den Schlüssel mitlesen. Nur für ein bekanntes, selbstsigniertes Zertifikat im
                Hausnetz - als letzte Option.
              </Alert>
            )}
          </Box>
        </AccordionDetails>
      </Accordion>

      {/* Stage 6: the connection test, one finding per scope */}
      <Box>
        <Button
          onClick={() => void testConnection()}
          disabled={testing || !endpointEntered || !keyComplete || !scopeEntered}
          variant="outlined"
        >
          {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
        </Button>
        {testMessage && (
          <Alert severity={testMessage.severity} sx={{ mt: 1 }}>
            {testMessage.text}
          </Alert>
        )}
        {scopeChecks && scopeChecks.length > 0 && (
          <List
            dense
            aria-label="Ergebnis je Geltungsbereich"
            data-testid={`${idPrefix}-scope-checks`}
            sx={{ mt: 0.5 }}
          >
            {scopeChecks.map((check) => (
              <ListItem
                key={s3ScopeKey({ bucket: check.bucket, prefix: check.prefix })}
                disableGutters
              >
                <Typography
                  sx={{
                    fontSize: 13,
                    fontFamily: 'monospace',
                    color: check.message ? 'warning.main' : 'text.primary',
                  }}
                >
                  {check.message ? '✗ ' : '✓ '}
                  {scopeCheckSummary(check)}
                </Typography>
              </ListItem>
            ))}
          </List>
        )}
      </Box>
    </Stack>
  )
}
