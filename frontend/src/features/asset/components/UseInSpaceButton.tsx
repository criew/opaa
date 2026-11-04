import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { AssetType, SpaceListResponse } from '@/shared/api/types'
import { associateSpaceAsset, getAssetSpaceAssociations } from '../api/assetApi'
import { notify } from '@/shared/stores/notificationStore'
import { successionAwareMessage } from '@/features/succession'
import type { SpaceCreateLocationState } from './assetPick'
import { useSpaceListLoader } from './spaceListContext'

interface UseInSpaceInlineProps {
  assetType: AssetType
  assetId: string
  name: string
  /**
   * The reach is frozen by an open succession (ADR-0036/6): every new association is refused, so
   * the field is locked and the form names why.
   */
  reachFrozen?: boolean
  /** Called after an association was created, e.g. to reload counts and lists. */
  onAssociated?: () => void
  /** Closes the form again (on „Abbrechen" and after a successful association). */
  onDone: () => void
}

/** The spaces in which the person may associate: CURATOR or ADMIN, and not archived. */
function curatedSpaces(spaces: SpaceListResponse[]): SpaceListResponse[] {
  return spaces.filter(
    (space) => !space.archived && (space.userRole === 'CURATOR' || space.userRole === 'ADMIN'),
  )
}

/**
 * The form behind „In Space verwenden" (ADR-0039, Entscheidung 4), inline below the section head
 * like „Freigeben": a search over the spaces the person curates and not yet holding the asset, a
 * „Verwenden" button, and „Neuen Space anlegen" that starts a space with the asset already chosen.
 */
export function UseInSpaceInline({
  assetType,
  assetId,
  name,
  reachFrozen = false,
  onAssociated,
  onDone,
}: UseInSpaceInlineProps) {
  const navigate = useNavigate()
  const loadSpaces = useSpaceListLoader()
  const [spaces, setSpaces] = useState<SpaceListResponse[] | null>(null)
  const [selected, setSelected] = useState<SpaceListResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let cancelled = false
    void Promise.all([
      loadSpaces(),
      getAssetSpaceAssociations(assetType, assetId).catch(() => ({ items: [] })),
    ])
      .then(([list, links]) => {
        if (cancelled) return
        const associated = new Set(links.items.map((item) => item.spaceId))
        // Already associated spaces are not offered - the association is the hard border, so a
        // second one carries no meaning.
        setSpaces(curatedSpaces(list).filter((space) => !associated.has(space.id)))
      })
      .catch((err) => {
        if (cancelled) return
        setSpaces([])
        setError(err instanceof Error ? err.message : 'Spaces konnten nicht geladen werden.')
      })
    return () => {
      cancelled = true
    }
  }, [assetType, assetId, loadSpaces])

  async function associate() {
    if (!selected) return
    setBusy(true)
    setError(null)
    try {
      await associateSpaceAsset(selected.id, assetType, assetId)
      notify(`„${name}“ ist jetzt im Space „${selected.name}“ zugeordnet.`, 'success')
      onAssociated?.()
      onDone()
    } catch (err) {
      setError(successionAwareMessage(err, 'Zuordnung fehlgeschlagen'))
    } finally {
      setBusy(false)
    }
  }

  function startNewSpace() {
    const state: SpaceCreateLocationState = { preselect: { assetType, assetId, name } }
    navigate('/spaces/new', { state })
  }

  if (spaces === null) {
    return (
      <Box sx={{ mb: 3, pb: 3, borderBottom: 1, borderColor: 'divider' }}>
        <Typography sx={{ color: 'text.secondary', fontSize: 13.5 }}>
          Spaces werden geladen …
        </Typography>
      </Box>
    )
  }

  return (
    <Box sx={{ mb: 3, pb: 3, borderBottom: 1, borderColor: 'divider' }}>
      <Stack spacing={1.5} sx={{ mt: 1 }}>
        {error && <Alert severity="error">{error}</Alert>}
        {reachFrozen ? (
          // Never dimmed: it holds the text contrast (2.4) and says why nothing can be chosen.
          <Typography sx={{ color: 'warning.main', fontSize: 13.5 }}>
            Solange die Nachfolge offen ist, lässt sich „{name}“ nicht neu zuordnen.
          </Typography>
        ) : (
          <>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.5}
              sx={{ alignItems: { sm: 'center' } }}
            >
              <Autocomplete
                sx={{ flex: 1, minWidth: 0 }}
                options={spaces}
                value={selected}
                onChange={(_event, next) => setSelected(next)}
                getOptionLabel={(space) => space.name}
                isOptionEqualToValue={(a, b) => a.id === b.id}
                disabled={busy}
                noOptionsText="Kein kuratierter Space gefunden"
                renderInput={(params) => (
                  <TextField
                    {...params}
                    size="small"
                    label="Space suchen"
                    placeholder="Name enthält …"
                  />
                )}
              />
              <Button
                variant="contained"
                onClick={() => void associate()}
                disabled={busy || !selected}
              >
                {busy ? 'Wird zugeordnet …' : 'Verwenden'}
              </Button>
            </Stack>
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              Angeboten werden nur Spaces, die Sie kuratieren und die „{name}“ noch nicht enthalten.{' '}
              <Link component="button" type="button" onClick={startNewSpace}>
                Neuen Space anlegen
              </Link>
            </Typography>
          </>
        )}
        <Box>
          <Button size="small" onClick={onDone} disabled={busy}>
            Abbrechen
          </Button>
        </Box>
      </Stack>
    </Box>
  )
}
