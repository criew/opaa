import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormHelperText from '@mui/material/FormHelperText'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type {
  CreatedExternalAccessTokenResponse,
  EligibleExternalAccessLibraryResponse,
} from '@/shared/api/types'
import {
  createExternalAccessToken,
  listEligibleExternalAccessLibraries,
} from '../api/externalAccessApi'
import { markAssetFavorite, unmarkAssetFavorite } from '@/features/asset'
import { AssetTile } from '@/features/asset'
import type { AssetTileData } from '@/features/asset'
import { type AssetFilterKey, type AssetFilters } from '@/features/asset'
import { AssetFilterBar } from '@/features/asset'
import { OWN_TOKENS_ROUTE } from '@/shared/routes'
import { isRecentLoginRequired, recentLoginRefusalMessage } from '@/shared/session/recentLogin'
import RecentLoginDialog from '@/shared/ui/RecentLoginDialog'
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
  /** The installation's maximum lifetime; it is also the default of the expiry date. */
  tokenMaxLifetimeDays: number
  onClose: () => void
  onCreated: (created: CreatedExternalAccessTokenResponse) => void
  /**
   * The page came back from a sign-in forced at the provider for this issuance; the draft did not
   * survive the redirect, so the dialog asks for it again.
   */
  afterProviderSignIn?: boolean
}

export const CONFIRM_PURPOSE =
  'Ein Zugangstoken wirkt über diese Sitzung hinaus. Bevor Sie es erzeugen, bestätigen Sie bitte ' +
  'Ihre Anmeldung.'

export const SIGN_IN_CONFIRMED_HINT =
  'Ihre Anmeldung ist bestätigt. Bitte geben Sie die Angaben erneut ein und erzeugen Sie das Token.'

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

const NO_FILTERS: AssetFilters = { favorites: false, selectedOnly: false }

function anyFilter(filters: AssetFilters): boolean {
  return filters.favorites || Boolean(filters.selectedOnly)
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
 * One tile per selectable library matching the search and the filters. Search and filters only
 * hide tiles, they never drop a choice. "Nur ausgewählte" and "Favoriten" filter by snapshots
 * taken when switched on (`shown`, `favoriteIds`), so a tile chosen away or unstarred stays and
 * the focus does not lose it. The tile's last line carries the end of the release.
 */
function libraryTiles(
  libraries: EligibleExternalAccessLibraryResponse[],
  query: string,
  filters: AssetFilters,
  shown: string[],
  favoriteIds: string[],
): AssetTileData[] {
  return libraries
    .filter((library) => matchesQuery(library, query))
    .filter((library) => !filters.favorites || favoriteIds.includes(library.id))
    .filter((library) => !filters.selectedOnly || shown.includes(library.id))
    .map((library) => ({
      assetType: 'KNOWLEDGE_LIBRARY',
      assetId: library.id,
      name: library.name,
      description: library.description ?? null,
      favorite: library.favorite,
      note: `Freigabe bis ${formatDate(library.releaseExpiresAt)}`,
    }))
}

/**
 * The creation dialog of an access token.
 *
 * The explanation stands as running text in the form and not in a footnote: it is the reason
 * this dialog is a deliberate action and not a button - what is granted here afterwards runs
 * through a tool about whose logging OPAA can promise nothing.
 *
 * The selection comes from `eligible-libraries` and is thus exactly the set the issuance
 * accepts - readable and released. Everything the dialog offers can be issued; offering one more
 * library would mean letting the person run into a rejection.
 *
 * The issuance requires a recent sign-in: a refusal for want of one opens the confirmation and,
 * once confirmed, submits the same draft again; a second refusal is explained, not asked again.
 */
export default function CreateExternalAccessTokenDialog({
  tokenMaxLifetimeDays,
  onClose,
  onCreated,
  afterProviderSignIn = false,
}: CreateExternalAccessTokenDialogProps) {
  const [libraries, setLibraries] = useState<EligibleExternalAccessLibraryResponse[] | null>(null)
  const [name, setName] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [query, setQuery] = useState('')
  const [filters, setFilters] = useState<AssetFilters>(NO_FILTERS)
  const [selectedSnapshot, setSelectedSnapshot] = useState<string[]>([])
  const [favoritesSnapshot, setFavoritesSnapshot] = useState<string[]>([])
  // The draft lives only as long as the dialog is mounted - the caller mounts it anew per
  // operation. A reset in an effect would be the same state, only one render later.
  const [expiresOn, setExpiresOn] = useState(() =>
    toDateInputValue(maxExpiryDate(tokenMaxLifetimeDays)),
  )
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [isSaving, setIsSaving] = useState(false)
  const [confirming, setConfirming] = useState(false)

  const latest = maxExpiryDate(tokenMaxLifetimeDays)
  const latestValue = toDateInputValue(latest)
  // Tomorrow at the earliest - the same the validation below requires. A day selectable in the
  // field that the validation next to it rejects would be a trap.
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
    () => libraryTiles(libraries ?? [], query, filters, selectedSnapshot, favoritesSnapshot),
    [libraries, query, filters, selectedSnapshot, favoritesSnapshot],
  )
  const searching = query.trim() !== ''
  const filtering = anyFilter(filters)

  async function setFavorite(libraryId: string, favorite: boolean) {
    try {
      if (favorite) await markAssetFavorite('KNOWLEDGE_LIBRARY', libraryId)
      else await unmarkAssetFavorite('KNOWLEDGE_LIBRARY', libraryId)
      setLibraries(
        (current) =>
          current?.map((library) =>
            library.id === libraryId ? { ...library, favorite } : library,
          ) ?? current,
      )
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Der Favorit konnte nicht gespeichert werden')
    }
  }

  function toggleFilter(key: AssetFilterKey) {
    if (key === 'selectedOnly' && !filters.selectedOnly) setSelectedSnapshot(selected)
    if (key === 'favorites' && !filters.favorites) {
      setFavoritesSnapshot((libraries ?? []).filter((l) => l.favorite).map((l) => l.id))
    }
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

  async function handleSubmit(afterConfirmation = false) {
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
      if (isRecentLoginRequired(err) && !afterConfirmation && !afterProviderSignIn) {
        setConfirming(true)
      } else if (isRecentLoginRequired(err)) {
        setError(recentLoginRefusalMessage(afterProviderSignIn && !afterConfirmation))
      } else {
        setError(err instanceof Error ? err.message : 'Das Token konnte nicht erzeugt werden.')
      }
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
        {afterProviderSignIn && !error && (
          <Alert severity="info" sx={{ mb: 2 }}>
            {SIGN_IN_CONFIRMED_HINT}
          </Alert>
        )}
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
                <Box sx={{ mt: 1, mb: 1.5 }}>
                  <AssetFilterBar
                    search={{ value: query, onChange: setQuery }}
                    filters={filters}
                    onToggle={toggleFilter}
                    trailing={
                      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                        {selected.length} ausgewählt
                      </Typography>
                    }
                  />
                </Box>
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
                  <Box
                    role="group"
                    aria-labelledby="create-token-libraries-title"
                    sx={{
                      display: 'grid',
                      gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)' },
                      gap: 2,
                    }}
                  >
                    {tiles.map((tile) => (
                      <AssetTile
                        key={tile.assetId}
                        tile={tile}
                        mode={{
                          kind: 'select',
                          selected: selected.includes(tile.assetId),
                          onToggle: () =>
                            setSelected((current) =>
                              current.includes(tile.assetId)
                                ? current.filter((id) => id !== tile.assetId)
                                : [...current, tile.assetId],
                            ),
                        }}
                        onFavoriteChange={(favorite) => setFavorite(tile.assetId, favorite)}
                      />
                    ))}
                  </Box>
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

          <Alert severity="info" role="note">
            <Typography sx={{ fontSize: 13 }}>{DISCLOSURE_HINT}</Typography>
          </Alert>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button variant="contained" onClick={() => void handleSubmit()} disabled={isSaving}>
          Erzeugen
        </Button>
      </DialogActions>
      <RecentLoginDialog
        open={confirming}
        purpose={CONFIRM_PURPOSE}
        onConfirmed={() => {
          setConfirming(false)
          void handleSubmit(true)
        }}
        onClose={() => setConfirming(false)}
        returnTo={OWN_TOKENS_ROUTE}
      />
    </Dialog>
  )
}
