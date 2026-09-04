import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { ConfluenceSpaceRef } from '../../types/api'
import { listConfluenceSpaces, testLibrarySource } from '../../services/api'
import { confluenceEditionLabel } from '../../utils/labels'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import FieldLabel from '../wizard/FieldLabel'

import {
  confluenceCredentialsOf,
  MAX_CONFLUENCE_SPACES,
  type ConfluenceSourceValues,
} from '../../utils/confluenceSource'

/** From this many selected spaces on, the limit is announced before the backend enforces it. */
const SPACE_LIMIT_HINT_FROM = 450

interface Message {
  severity: 'success' | 'warning' | 'error'
  text: string
}

interface ConfluenceSourceFormProps {
  mode: 'create' | 'edit'
  values: ConfluenceSourceValues
  onChange: (patch: Partial<ConfluenceSourceValues>) => void
  /** Edit mode: lets the test and the space listing fall back to the stored credentials. */
  libraryId?: string
  /** Edit mode: whether credentials are stored for this library. */
  credentialsStored?: boolean
  /** Edit mode: the address the stored credentials belong to - they do not survive a host change. */
  originalSourceUrl?: string | null
  idPrefix: string
}

function spaceLabel(space: ConfluenceSpaceRef): string {
  return space.name ? `${space.name} (${space.key})` : space.key
}

/**
 * The staged Confluence form (ADR-0023): the address first, then the edition is *detected* from
 * the instance - never chosen -, then the credentials in the shape that edition needs, a
 * connection test, and only after a successful test the space selection - preceded by the one
 * sentence the selection must not be made without: everything indexed from every selected space is
 * readable by everyone who may read this library.
 *
 * <p>Every answer from the instance is checked against a generation counter that each edit of the
 * address or the credentials advances: a late answer for a since-changed input is dropped, so
 * "geprüft" only ever refers to what is on screen.
 */
export default function ConfluenceSourceForm({
  mode,
  values,
  onChange,
  libraryId,
  credentialsStored = false,
  originalSourceUrl,
  idPrefix,
}: ConfluenceSourceFormProps) {
  const [detecting, setDetecting] = useState(false)
  const [detectMessage, setDetectMessage] = useState<Message | null>(null)
  const [testing, setTesting] = useState(false)
  const [testMessage, setTestMessage] = useState<Message | null>(null)
  const [availableSpaces, setAvailableSpaces] = useState<ConfluenceSpaceRef[] | null>(null)
  const [loadingSpaces, setLoadingSpaces] = useState(false)
  const [spacesError, setSpacesError] = useState<string | null>(null)
  const spacesRequested = useRef(false)
  const generation = useRef(0)

  const editionFixed = mode === 'edit' && values.edition !== null
  // #542 review finding 1, mirrored for Confluence: the backend carries the stored token forward
  // only while the address still names the same origin; a host change drops it.
  const originChanged =
    mode === 'edit' &&
    credentialsStored &&
    values.sourceUrl.trim() !== '' &&
    !sameLibrarySourceOrigin(originalSourceUrl, values.sourceUrl)
  const usesStoredCredentials =
    mode === 'edit' &&
    credentialsStored &&
    !originChanged &&
    values.token.trim() === '' &&
    libraryId !== undefined
  const emailMissingForNewToken =
    mode === 'edit' &&
    values.edition === 'CLOUD' &&
    values.token.trim() !== '' &&
    values.email.trim() === ''

  const connectionPayload = useCallback(
    () => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceProxy: values.sourceProxy.trim() || undefined,
      sourceInsecureSsl: values.sourceInsecureSsl,
    }),
    [values.sourceUrl, values.sourceProxy, values.sourceInsecureSsl],
  )

  const loadSpaces = useCallback(async () => {
    if (!values.edition) return
    const mine = generation.current
    setLoadingSpaces(true)
    setSpacesError(null)
    try {
      const result = await listConfluenceSpaces({
        ...connectionPayload(),
        confluenceEdition: values.edition,
        sourceCredentials: confluenceCredentialsOf(values),
        libraryId: usesStoredCredentials ? libraryId : undefined,
      })
      if (generation.current !== mine) return
      setAvailableSpaces(result.spaces)
    } catch (err) {
      if (generation.current !== mine) return
      setAvailableSpaces(null)
      setSpacesError(err instanceof Error ? err.message : 'Die Spaces konnten nicht geladen werden')
    } finally {
      if (generation.current === mine) setLoadingSpaces(false)
    }
  }, [connectionPayload, libraryId, usesStoredCredentials, values])

  // Verified credentials without a listing yet - the stored ones in edit mode, or the wizard
  // remounting this step after "Zurück" - load the spaces right away, once.
  useEffect(() => {
    if (values.credentialsVerified && availableSpaces === null && !spacesRequested.current) {
      spacesRequested.current = true
      void loadSpaces()
    }
  }, [values.credentialsVerified, availableSpaces, loadSpaces])

  function invalidateListing() {
    generation.current += 1
    setTestMessage(null)
    setAvailableSpaces(null)
    setSpacesError(null)
    setLoadingSpaces(false)
    setTesting(false)
    spacesRequested.current = false
  }

  // The selection survives every edit (a proxy typo must not wipe a curated list); what the new
  // listing cannot read is shown as such below, and only the verification is withdrawn.
  function changeAddress(patch: Partial<ConfluenceSourceValues>) {
    invalidateListing()
    setDetectMessage(null)
    setDetecting(false)
    onChange({
      ...patch,
      // the edition belongs to the instance behind the address - a new address means a new detection
      edition: editionFixed ? values.edition : null,
      credentialsVerified: false,
    })
  }

  function changeCredentials(patch: Partial<ConfluenceSourceValues>) {
    invalidateListing()
    onChange({ ...patch, credentialsVerified: false })
  }

  async function detectEdition() {
    const mine = generation.current
    setDetecting(true)
    setDetectMessage(null)
    try {
      const result = await testLibrarySource({
        sourceType: 'CONFLUENCE',
        ...connectionPayload(),
      })
      if (generation.current !== mine) return
      if (result.confluenceEdition) {
        onChange({ edition: result.confluenceEdition, credentialsVerified: false })
        setDetectMessage({ severity: 'success', text: result.message })
      } else {
        onChange({ edition: null, credentialsVerified: false })
        setDetectMessage({ severity: result.reachable ? 'warning' : 'error', text: result.message })
      }
    } catch (err) {
      if (generation.current !== mine) return
      setDetectMessage({
        severity: 'error',
        text: err instanceof Error ? err.message : 'Die Edition konnte nicht erkannt werden',
      })
    } finally {
      if (generation.current === mine) setDetecting(false)
    }
  }

  async function testConnection() {
    if (!values.edition) return
    const mine = generation.current
    setTesting(true)
    setTestMessage(null)
    try {
      const result = await testLibrarySource({
        sourceType: 'CONFLUENCE',
        ...connectionPayload(),
        confluenceEdition: values.edition,
        sourceCredentials: confluenceCredentialsOf(values),
        libraryId: usesStoredCredentials ? libraryId : undefined,
      })
      if (generation.current !== mine) return
      if (result.credentialsVerified) {
        onChange({ credentialsVerified: true })
        setTestMessage({ severity: 'success', text: result.message })
        spacesRequested.current = true
        void loadSpaces()
      } else {
        onChange({ credentialsVerified: false })
        setTestMessage({ severity: result.reachable ? 'warning' : 'error', text: result.message })
      }
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

  const credentialsComplete =
    values.edition === 'CLOUD'
      ? (values.email.trim() !== '' && values.token.trim() !== '') || usesStoredCredentials
      : values.token.trim() !== '' || usesStoredCredentials

  // Selected spaces the current listing does not contain stay selected and visibly marked; they
  // are offered as options too so the picker never carries a value it cannot name.
  const unreadableKeys = useMemo(() => {
    if (availableSpaces === null) return new Set<string>()
    const readable = new Set(availableSpaces.map((space) => space.key))
    return new Set(values.spaces.filter((s) => !readable.has(s.key)).map((s) => s.key))
  }, [availableSpaces, values.spaces])
  const options = useMemo(() => {
    const listed = availableSpaces ?? []
    const listedKeys = new Set(listed.map((space) => space.key))
    return [...listed, ...values.spaces.filter((space) => !listedKeys.has(space.key))]
  }, [availableSpaces, values.spaces])
  const optionLabel = (space: ConfluenceSpaceRef) =>
    unreadableKeys.has(space.key)
      ? `${spaceLabel(space)} – derzeit nicht lesbar`
      : spaceLabel(space)

  const tokenHelperText = originChanged
    ? 'Die Adresse zeigt auf einen anderen Server — das hinterlegte Token gilt dort nicht und muss neu eingegeben werden.'
    : mode === 'edit' && credentialsStored
      ? 'Leer lassen, um das hinterlegte Token beizubehalten. Wird nie in einer API-Antwort ausgegeben.'
      : 'Wird nie in einer API-Antwort ausgegeben.'

  return (
    <Stack spacing={2.5}>
      {/* Stage 1: address */}
      <Box>
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
          Verbindung zu Confluence
        </Typography>
        <Box
          sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: '14px' }}
        >
          <Box sx={{ gridColumn: '1 / -1' }}>
            <FieldLabel htmlFor={`${idPrefix}-url`}>Adresse der Confluence-Instanz</FieldLabel>
            <TextField
              id={`${idPrefix}-url`}
              size="small"
              value={values.sourceUrl}
              onChange={(e) => changeAddress({ sourceUrl: e.target.value })}
              placeholder="https://wiki.behoerde.example/confluence"
              helperText="Cloud: die Adresse Ihrer Site, mit oder ohne /wiki. Data Center: die Adresse samt Kontextpfad."
              fullWidth
              slotProps={{ htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } } }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor={`${idPrefix}-proxy`}>Proxy (optional)</FieldLabel>
            <TextField
              id={`${idPrefix}-proxy`}
              size="small"
              value={values.sourceProxy}
              onChange={(e) => changeAddress({ sourceProxy: e.target.value })}
              placeholder="proxy.example.com:8080"
              autoComplete="off"
              fullWidth
              slotProps={{ htmlInput: { maxLength: 255 } }}
            />
          </Box>
          <FormControlLabel
            sx={{ gridColumn: '1 / -1' }}
            control={
              <Switch
                checked={values.sourceInsecureSsl}
                onChange={(e) => changeAddress({ sourceInsecureSsl: e.target.checked })}
              />
            }
            label="Zertifikatsprüfung aussetzen"
          />
        </Box>
        {!editionFixed && (
          <Box sx={{ mt: 1.5 }}>
            <Button
              onClick={() => void detectEdition()}
              disabled={detecting || values.sourceUrl.trim() === ''}
              variant="outlined"
            >
              {detecting ? 'Edition wird erkannt …' : 'Edition erkennen'}
            </Button>
            {detectMessage && (
              <Alert severity={detectMessage.severity} sx={{ mt: 1 }}>
                {detectMessage.text}
              </Alert>
            )}
          </Box>
        )}
        {values.edition && (
          <Stack direction="row" spacing={1} sx={{ mt: 1.5, alignItems: 'center' }}>
            <Typography variant="body2">
              <strong>Edition:</strong>
            </Typography>
            <Chip
              size="small"
              label={`Confluence ${confluenceEditionLabel(values.edition)}`}
              data-testid={`${idPrefix}-edition`}
            />
            <Typography variant="caption" color="text.secondary">
              {editionFixed
                ? 'nach der Anlage nicht änderbar'
                : 'erkannt aus der Antwort der Instanz — nach der Anlage nicht änderbar'}
            </Typography>
          </Stack>
        )}
      </Box>

      {/* Stage 2: credentials in the edition's shape */}
      {values.edition && (
        <Box>
          <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
            Zugangsdaten des Dienstkontos
          </Typography>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
              gap: '14px',
            }}
          >
            {values.edition === 'CLOUD' && (
              <Box>
                <FieldLabel htmlFor={`${idPrefix}-email`}>E-Mail-Adresse</FieldLabel>
                <TextField
                  id={`${idPrefix}-email`}
                  size="small"
                  type="email"
                  value={values.email}
                  onChange={(e) => changeCredentials({ email: e.target.value })}
                  placeholder="dienstkonto@behoerde.example"
                  error={emailMissingForNewToken}
                  helperText={
                    emailMissingForNewToken
                      ? 'Bei einem neuen API-Token bitte auch die E-Mail-Adresse des Dienstkontos erneut eingeben.'
                      : 'Das Atlassian-Konto, zu dem das API-Token gehört.'
                  }
                  autoComplete="off"
                  fullWidth
                  slotProps={{ htmlInput: { maxLength: 320 } }}
                />
              </Box>
            )}
            <Box>
              <FieldLabel htmlFor={`${idPrefix}-token`}>
                {values.edition === 'CLOUD'
                  ? mode === 'edit'
                    ? 'Neues API-Token'
                    : 'API-Token'
                  : mode === 'edit'
                    ? 'Neues Personal Access Token'
                    : 'Personal Access Token'}
              </FieldLabel>
              <TextField
                id={`${idPrefix}-token`}
                size="small"
                type="password"
                value={values.token}
                onChange={(e) => changeCredentials({ token: e.target.value })}
                error={originChanged && values.token.trim() === ''}
                helperText={tokenHelperText}
                autoComplete="new-password"
                fullWidth
                slotProps={{ htmlInput: { maxLength: 500 } }}
              />
            </Box>
          </Box>
          <Box sx={{ mt: 1.5 }}>
            <Button
              onClick={() => void testConnection()}
              disabled={testing || !credentialsComplete}
              variant="outlined"
            >
              {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
            </Button>
            {testMessage && (
              <Alert severity={testMessage.severity} sx={{ mt: 1 }}>
                {testMessage.text}
              </Alert>
            )}
          </Box>
        </Box>
      )}

      {/* Stage 3: the consequence, stated before the selection - not after it */}
      {values.edition && (
        <Alert severity="warning" data-testid={`${idPrefix}-sharing-consequence`}>
          <strong>
            Wer diese Bibliothek lesen darf, sieht alles aus allen ausgewählten Spaces.
          </strong>{' '}
          Die Berechtigungen in Confluence enden mit der Indizierung; OPAA übernimmt sie nicht.
          Sollen Personen einen Space lesen dürfen, einen anderen aber nicht, legen Sie dafür eine
          zweite Bibliothek mit eigener Auswahl an.
        </Alert>
      )}

      {/* Stage 4: space selection, only after a successful test */}
      {values.edition && (
        <Box aria-busy={loadingSpaces}>
          <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
            Ausgewählte Spaces
          </Typography>
          {!values.credentialsVerified && (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Die Spaces stehen zur Auswahl, sobald die Zugangsdaten erfolgreich getestet wurden.
              {values.spaces.length > 0 &&
                ` Die bisherige Auswahl (${values.spaces.map((s) => s.key).join(', ')}) bleibt bestehen.`}
            </Typography>
          )}
          {values.credentialsVerified && spacesError && (
            <Alert
              severity="error"
              sx={{ mb: 1.5 }}
              action={
                <Button color="inherit" size="small" onClick={() => void loadSpaces()}>
                  Erneut laden
                </Button>
              }
            >
              {spacesError}
            </Alert>
          )}
          {values.credentialsVerified && unreadableKeys.size > 0 && (
            <Alert severity="warning" sx={{ mb: 1.5 }}>
              Mit den geprüften Zugangsdaten derzeit nicht lesbar: {[...unreadableKeys].join(', ')}.
              Diese Spaces bleiben ausgewählt, bis Sie sie entfernen; ein Lauf überspringt sie
              sichtbar.
            </Alert>
          )}
          {values.credentialsVerified && (
            <>
              <FieldLabel htmlFor={`${idPrefix}-spaces`}>Spaces suchen und auswählen</FieldLabel>
              <Autocomplete<ConfluenceSpaceRef, true, false, false>
                id={`${idPrefix}-spaces`}
                multiple
                disableCloseOnSelect
                loading={loadingSpaces}
                options={options}
                value={values.spaces}
                onChange={(_e, selected) => onChange({ spaces: selected })}
                getOptionLabel={optionLabel}
                isOptionEqualToValue={(a, b) => a.key === b.key}
                noOptionsText={
                  loadingSpaces
                    ? 'Spaces werden geladen …'
                    : spacesError
                      ? 'Die Spaces konnten nicht geladen werden'
                      : 'Kein Space passt zur Suche'
                }
                loadingText="Spaces werden geladen …"
                renderOption={(props, space, { selected }) => {
                  const { key, ...optionProps } = props
                  return (
                    <li key={key} {...optionProps}>
                      <Checkbox size="small" checked={selected} sx={{ mr: 1 }} tabIndex={-1} />
                      {optionLabel(space)}
                    </li>
                  )
                }}
                renderValue={(selected, getItemProps) =>
                  selected.map((space, index) => (
                    <Chip
                      {...getItemProps({ index })}
                      key={space.key}
                      size="small"
                      color={unreadableKeys.has(space.key) ? 'warning' : 'default'}
                      label={optionLabel(space)}
                    />
                  ))
                }
                renderInput={(params) => (
                  <TextField
                    {...params}
                    size="small"
                    placeholder={
                      values.spaces.length === 0 ? 'Space-Name oder -Schlüssel eingeben' : ''
                    }
                  />
                )}
              />
              <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.75 }}>
                {availableSpaces === null
                  ? loadingSpaces
                    ? 'Spaces werden geladen …'
                    : `${values.spaces.length} Spaces ausgewählt.`
                  : `${values.spaces.length} von ${availableSpaces.length} lesbaren Spaces ausgewählt.`}
                {values.spaces.length >= SPACE_LIMIT_HINT_FROM &&
                  ` Höchstens ${MAX_CONFLUENCE_SPACES} Spaces je Bibliothek.`}
                {mode === 'edit' &&
                  ' Ein entfernter Space verschwindet mit dem nächsten Vollabgleich aus dem Index; jede Änderung der Auswahl macht den nächsten Lauf zu einem Vollabgleich.'}
              </Typography>
            </>
          )}
        </Box>
      )}
    </Stack>
  )
}
