import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormHelperText from '@mui/material/FormHelperText'
import InputAdornment from '@mui/material/InputAdornment'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import SearchIcon from '@mui/icons-material/Search'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import type {
  CreatedExternalAccessTokenResponse,
  EligibleExternalAccessLibraryResponse,
} from '../../types/api'
import {
  createExternalAccessToken,
  listEligibleExternalAccessLibraries,
} from '../../services/externalAccessApi'
import { radius } from '../../theme/tokens'
import ChoiceTileGroup, { type ChoiceTile } from '../choice/ChoiceTileGroup'
import { assetTypeDefinition } from '../assets/assetTypeRegistry'
import {
  DISCLOSURE_HINT,
  NO_LIBRARIES_HINT,
  SELECTION_IS_FINAL_HINT,
  earliestExpiryDate,
  expiryInstantOf,
  formatDate,
  maxExpiryDate,
  releaseEndsBeforeExpiry,
  toDateInputValue,
} from './tokenLabels'

interface CreateExternalAccessTokenDialogProps {
  /** Die Höchstlaufzeit der Installation; sie ist zugleich die Vorgabe des Ablaufdatums. */
  tokenMaxLifetimeDays: number
  onClose: () => void
  onCreated: (created: CreatedExternalAccessTokenResponse) => void
}

interface FieldErrors {
  name?: string
  libraryIds?: string
  expiresAt?: string
}

function matchesQuery(library: EligibleExternalAccessLibraryResponse, query: string): boolean {
  const haystack = `${library.name} ${library.description ?? ''}`.toLowerCase()
  return query
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
    .every((term) => haystack.includes(term))
}

/** The filters above the tiles; all of them only hide tiles and combine with AND. */
interface TileFilters {
  favorites: boolean
  fromMyGroups: boolean
  selectedOnly: boolean
}

const NO_FILTERS: TileFilters = { favorites: false, fromMyGroups: false, selectedOnly: false }

function anyFilter(filters: TileFilters): boolean {
  return filters.favorites || filters.fromMyGroups || filters.selectedOnly
}

function resultMessage(count: number, searching: boolean, filtering: boolean): string {
  const by =
    searching && filtering
      ? 'zur Suche und zu den Filtern'
      : searching
        ? 'zur Suche'
        : 'zu den Filtern'
  if (count === 0) return `Keine Bibliothek passt ${by}.`
  return count === 1 ? `1 Bibliothek passt ${by}.` : `${count} Bibliotheken passen ${by}.`
}

/**
 * One tile per selectable library matching the search and the filters (guidelines 5.11). Search
 * and filters only hide tiles, they never drop a choice. The sentence under the name carries the
 * end of the release: if it ends before the token, the token loses the library first.
 */
function libraryTiles(
  libraries: EligibleExternalAccessLibraryResponse[],
  query: string,
  filters: TileFilters,
  selected: string[],
): ChoiceTile<string>[] {
  const Icon = assetTypeDefinition('KNOWLEDGE_LIBRARY')?.Icon
  return libraries
    .filter((library) => matchesQuery(library, query))
    .filter((library) => !filters.favorites || library.favorite)
    .filter((library) => !filters.fromMyGroups || library.fromMyGroups)
    .filter((library) => !filters.selectedOnly || selected.includes(library.id))
    .map((library) => ({
      value: library.id,
      label: library.name,
      icon: Icon ? <Icon sx={{ fontSize: 20 }} /> : null,
      description: (
        <>
          {library.description && (
            <Box
              component="span"
              sx={{
                display: '-webkit-box',
                WebkitLineClamp: 2,
                WebkitBoxOrient: 'vertical',
                overflow: 'hidden',
              }}
            >
              {library.description}
            </Box>
          )}
          <Box component="span" sx={{ display: 'block' }}>
            Freigabe bis {formatDate(library.releaseExpiresAt)}
          </Box>
        </>
      ),
    }))
}

/**
 * Der Anlegedialog eines Zugangstokens (#1719).
 *
 * Die Aufklärung steht als Fließtext im Formular und nicht in einer Fußnote: Sie ist der Grund,
 * aus dem dieser Dialog eine bewusste Handlung ist und kein Knopf - was hier erteilt wird, läuft
 * anschließend durch ein Werkzeug, über dessen Protokollierung OPAA nichts zusagen kann.
 *
 * Die Auswahl kommt aus `eligible-libraries` und ist damit genau die Menge, die die Ausstellung
 * annimmt - lesbar und freigegeben. Alles, was der Dialog anbietet, ist ausstellbar; eine
 * Bibliothek mehr anzubieten hieße, die Person in eine Abweisung laufen zu lassen.
 */
export default function CreateExternalAccessTokenDialog({
  tokenMaxLifetimeDays,
  onClose,
  onCreated,
}: CreateExternalAccessTokenDialogProps) {
  const [libraries, setLibraries] = useState<EligibleExternalAccessLibraryResponse[] | null>(null)
  const [name, setName] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [query, setQuery] = useState('')
  const [filters, setFilters] = useState<TileFilters>(NO_FILTERS)
  // Der Entwurf lebt nur, solange der Dialog montiert ist - der Aufrufer montiert ihn je Vorgang
  // neu. Ein Zurücksetzen im Effekt wäre derselbe Zustand, nur einen Renderdurchlauf später.
  const [expiresOn, setExpiresOn] = useState(() =>
    toDateInputValue(maxExpiryDate(tokenMaxLifetimeDays)),
  )
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [isSaving, setIsSaving] = useState(false)

  const latest = maxExpiryDate(tokenMaxLifetimeDays)
  const latestValue = toDateInputValue(latest)
  // Frühestens morgen - dasselbe, was die Prüfung unten verlangt. Ein im Feld wählbarer Tag, den
  // die Prüfung daneben ablehnt, wäre eine Falle.
  const earliestValue = toDateInputValue(earliestExpiryDate())

  useEffect(() => {
    let active = true
    listEligibleExternalAccessLibraries()
      .then((loaded) => {
        if (active) setLibraries(loaded)
      })
      .catch((err: unknown) => {
        if (!active) return
        setLibraries([])
        setError(
          err instanceof Error ? err.message : 'Die Bibliotheken konnten nicht geladen werden.',
        )
      })
    return () => {
      active = false
    }
  }, [])

  const tiles = useMemo(
    () => libraryTiles(libraries ?? [], query, filters, selected),
    [libraries, query, filters, selected],
  )
  const searching = query.trim() !== ''
  const filtering = anyFilter(filters)

  function toggleFilter(key: keyof TileFilters) {
    setFilters((current) => ({ ...current, [key]: !current[key] }))
  }

  function validate(): FieldErrors {
    const errors: FieldErrors = {}
    if (name.trim() === '') {
      errors.name =
        'Bitte geben Sie einen Namen an - er ist später das Einzige, woran Sie dieses Token erkennen.'
    }
    if (selected.length === 0) {
      errors.libraryIds = 'Bitte wählen Sie mindestens eine Bibliothek aus.'
    }
    if (expiresOn === '') {
      errors.expiresAt = 'Bitte geben Sie ein Ablaufdatum an - ein Token ohne Ablauf gibt es nicht.'
    } else if (expiresOn > latestValue) {
      errors.expiresAt = `Das Ablaufdatum liegt höchstens ${tokenMaxLifetimeDays} Tage in der Zukunft, also spätestens am ${latest.toLocaleDateString('de-DE')}.`
    } else if (expiresOn < earliestValue) {
      errors.expiresAt = 'Das Ablaufdatum muss in der Zukunft liegen, frühestens morgen.'
    }
    return errors
  }

  async function handleSubmit() {
    const errors = validate()
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) return
    setError(null)
    setIsSaving(true)
    try {
      const created = await createExternalAccessToken({
        name: name.trim(),
        libraryIds: selected,
        expiresAt: expiryInstantOf(expiresOn, latest),
      })
      onCreated(created)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Das Token konnte nicht erzeugt werden.')
    } finally {
      setIsSaving(false)
    }
  }

  const hasLibraries = libraries !== null && libraries.length > 0
  const endingReleases =
    expiresOn === ''
      ? []
      : releaseEndsBeforeExpiry(libraries ?? [], selected, expiryInstantOf(expiresOn, latest))

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose} aria-labelledby="create-token-title">
      <DialogTitle id="create-token-title">Token erzeugen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={3} sx={{ mt: 1 }}>
          <TextField
            label="Name / Zweck"
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={Boolean(fieldErrors.name)}
            helperText={
              fieldErrors.name ??
              'Zum Beispiel „Claude Code auf dem Dienstrechner“ - nicht „Token 3“.'
            }
            slotProps={{ htmlInput: { maxLength: 120 } }}
            fullWidth
          />

          <Box>
            <Typography id="create-token-libraries-title" sx={{ fontSize: 13.5, fontWeight: 500 }}>
              Bibliotheken
            </Typography>
            {libraries === null ? (
              <Skeleton variant="rounded" height={96} sx={{ mt: 1 }} />
            ) : hasLibraries ? (
              <>
                <Stack
                  direction="row"
                  spacing={1.5}
                  sx={{ alignItems: 'center', mt: 1, mb: 1.5, flexWrap: 'wrap' }}
                >
                  <TextField
                    type="search"
                    size="small"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder="Name oder Beschreibung …"
                    sx={{ flex: '1 1 220px' }}
                    slotProps={{
                      input: {
                        startAdornment: (
                          <InputAdornment position="start">
                            <SearchIcon sx={{ fontSize: 16 }} />
                          </InputAdornment>
                        ),
                      },
                      htmlInput: { 'aria-label': 'Bibliotheken suchen' },
                    }}
                  />
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    {selected.length} ausgewählt
                  </Typography>
                </Stack>
                <Stack
                  direction="row"
                  spacing={1}
                  useFlexGap
                  sx={{ flexWrap: 'wrap', mb: 1.5 }}
                  role="group"
                  aria-label="Filter"
                >
                  <Chip
                    label="Alle"
                    size="small"
                    variant={filtering ? 'outlined' : 'filled'}
                    aria-pressed={!filtering}
                    onClick={() => setFilters(NO_FILTERS)}
                  />
                  <Chip
                    label="Favoriten"
                    size="small"
                    icon={filters.favorites ? <StarIcon /> : <StarBorderIcon />}
                    variant={filters.favorites ? 'filled' : 'outlined'}
                    aria-pressed={filters.favorites}
                    onClick={() => toggleFilter('favorites')}
                  />
                  <Chip
                    label="Aus meinen Gruppen"
                    size="small"
                    variant={filters.fromMyGroups ? 'filled' : 'outlined'}
                    aria-pressed={filters.fromMyGroups}
                    onClick={() => toggleFilter('fromMyGroups')}
                  />
                  <Chip
                    label="Nur ausgewählte"
                    size="small"
                    variant={filters.selectedOnly ? 'filled' : 'outlined'}
                    aria-pressed={filters.selectedOnly}
                    onClick={() => toggleFilter('selectedOnly')}
                  />
                </Stack>
                {/* Searching and filtering do not move the focus, so the result is announced in a
                    live region. Without a match the same message is visible as well. */}
                <Box
                  role="status"
                  aria-live="polite"
                  sx={
                    tiles.length === 0 ? { fontSize: 13, color: 'text.secondary' } : visuallyHidden
                  }
                >
                  {searching || filtering ? resultMessage(tiles.length, searching, filtering) : ''}
                </Box>
                {tiles.length > 0 && (
                  <ChoiceTileGroup<string>
                    multiple
                    aria-labelledby="create-token-libraries-title"
                    tiles={tiles}
                    value={selected}
                    onChange={setSelected}
                  />
                )}
                <FormHelperText error={Boolean(fieldErrors.libraryIds)}>
                  {fieldErrors.libraryIds ?? SELECTION_IS_FINAL_HINT}
                </FormHelperText>
              </>
            ) : (
              <Alert severity="info" sx={{ mt: 1 }}>
                {NO_LIBRARIES_HINT}
              </Alert>
            )}
          </Box>

          <TextField
            label="Läuft ab"
            type="date"
            value={expiresOn}
            onChange={(e) => setExpiresOn(e.target.value)}
            error={Boolean(fieldErrors.expiresAt)}
            helperText={
              fieldErrors.expiresAt ??
              `Höchstens ${tokenMaxLifetimeDays} Tage, also spätestens am ${latest.toLocaleDateString('de-DE')}.`
            }
            slotProps={{
              inputLabel: { shrink: true },
              htmlInput: { min: earliestValue, max: latestValue },
            }}
            fullWidth
          />

          {endingReleases.length > 0 && (
            <Alert severity="info">
              Die Freigabe von {endingReleases.join(', ')} endet vor diesem Ablaufdatum. Ab dann
              wirkt die Bibliothek in diesem Token nicht mehr und lebt auch bei einer erneuten
              Freigabe nicht wieder auf - dafür wäre ein neues Token nötig.
            </Alert>
          )}

          <Box>
            <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>Rechte</Typography>
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Nur lesen und suchen. Der Umfang ist fest und nicht wählbar.
            </Typography>
          </Box>

          <Box
            sx={{
              p: 1.5,
              border: 1,
              borderColor: 'divider',
              borderRadius: `${radius.sm}px`,
            }}
          >
            <Typography sx={{ fontSize: 13 }}>{DISCLOSURE_HINT}</Typography>
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button variant="contained" onClick={() => void handleSubmit()} disabled={isSaving}>
          Erzeugen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
