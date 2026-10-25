import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Skeleton from '@mui/material/Skeleton'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import LinkOffIcon from '@mui/icons-material/LinkOff'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import StateDot from '@/shared/ui/StateDot'
import type {
  AssetSpaceAssociationListResponse,
  AssetSpaceAssociationResponse,
  AssetType,
} from '@/shared/api/types'
import { detachSpaceAsset, getAssetSpaceAssociations } from '../api/assetApi'
import { useAuthStore } from '@/shared/session/authStore'
import { confirmAction } from '@/shared/stores/confirmStore'
import { assetTypeLabel } from '../../../shared/assetTypes'
import { useSpaceListLoader } from './spaceListContext'

/** From this many spaces the list gets a search (D19). */
const SEARCH_FROM = 8

interface AssetSpacesListProps {
  assetType: AssetType
  assetId: string
  /**
   * Whether the caller may manage the asset. Only then does the endpoint answer with creator, time
   * and reader circle of each space, and only then may every association be detached. Without it
   * a row can still be detached by whoever curates its space.
   */
  canManage: boolean
  /** Called after an association was detached. */
  onChanged?: () => void
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/** Who associated and when - both only ever sent to a caller who manages the asset. */
function provenance(association: AssetSpaceAssociationResponse): string | null {
  const parts = [
    association.createdByDisplayName ? `zugeordnet von ${association.createdByDisplayName}` : null,
    association.createdAt ? formatDate(association.createdAt) : null,
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(' · ') : null
}

/** The row's „⋯" with its one entry, „Aus Space lösen". */
function RowActions({ spaceName, onDetach }: { spaceName: string; onDetach: () => void }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  return (
    <>
      <IconButton
        size="small"
        aria-label={`Aktionen für Space „${spaceName}“`}
        aria-haspopup="menu"
        aria-expanded={anchor ? true : undefined}
        onClick={(event) => setAnchor(event.currentTarget)}
      >
        <MoreHorizIcon fontSize="small" />
      </IconButton>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        <MenuItem
          onClick={() => {
            setAnchor(null)
            onDetach()
          }}
        >
          <LinkOffIcon aria-hidden sx={{ fontSize: 18, mr: 1.5 }} />
          Aus Space lösen
        </MenuItem>
      </Menu>
    </>
  )
}

/**
 * The spaces an asset is associated with (ADR-0039). A manager of the asset sees every
 * association with who made it and when, and may detach each one unilaterally; whoever curates a
 * space may detach the asset from that space. A plain reader sees the names of the spaces they
 * may know of and, as a number alone, how many further ones there are.
 */
export default function AssetSpacesList({
  assetType,
  assetId,
  canManage,
  onChanged,
}: AssetSpacesListProps) {
  const [links, setLinks] = useState<AssetSpaceAssociationListResponse | null>(null)
  const [curatedSpaceIds, setCuratedSpaceIds] = useState<ReadonlySet<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const loadSpaces = useSpaceListLoader()
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const noun = assetTypeLabel(assetType)

  useEffect(() => {
    let cancelled = false
    getAssetSpaceAssociations(assetType, assetId)
      .then((data) => {
        if (!cancelled) setLinks(data)
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Laden fehlgeschlagen')
      })
    return () => {
      cancelled = true
    }
  }, [assetType, assetId])

  useEffect(() => {
    let cancelled = false
    loadSpaces()
      .then((spaces) => {
        if (cancelled) return
        setCuratedSpaceIds(
          new Set(
            spaces
              .filter((space) => space.userRole === 'CURATOR' || space.userRole === 'ADMIN')
              .map((space) => space.id),
          ),
        )
      })
      // Without the own space roles a row simply offers no detaching beyond the asset's own right.
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [loadSpaces])

  function mayDetach(spaceId: string): boolean {
    return canManage || isSystemAdmin || curatedSpaceIds.has(spaceId)
  }

  async function handleDetach(spaceId: string, spaceName: string) {
    const confirmed = await confirmAction({
      question: `Zuordnung zum Space „${spaceName}“ lösen?`,
      consequence: `Die ${noun} steht in diesem Space danach nicht mehr zur Verfügung.`,
      confirmLabel: 'Lösen',
      tone: 'neutral',
    })
    if (!confirmed) return
    setError(null)
    try {
      await detachSpaceAsset(spaceId, assetId)
      setLinks((prev) =>
        prev ? { ...prev, items: prev.items.filter((a) => a.spaceId !== spaceId) } : null,
      )
      onChanged?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Lösen fehlgeschlagen')
    }
  }

  const needle = query.trim().toLowerCase()
  const matching = (links?.items ?? []).filter(
    (association) => !needle || association.spaceName.toLowerCase().includes(needle),
  )
  const hiddenCount = links?.hiddenCount ?? 0
  const hiddenNote =
    hiddenCount === 1
      ? '+ 1 weiterer Space, den Sie nicht sehen dürfen'
      : `+ ${hiddenCount} weitere Spaces, die Sie nicht sehen dürfen`

  return (
    <Box>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {links === null ? (
        // Loading without a layout jump (guidelines 5.7) - one skeleton row where the first
        // association will land.
        <Skeleton variant="rounded" height={32} sx={{ maxWidth: 360 }} />
      ) : links.items.length === 0 && hiddenCount === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Noch keinem Space zugeordnet.
        </Typography>
      ) : (
        <>
          {links.items.length >= SEARCH_FROM && (
            <TextField
              size="small"
              fullWidth
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Space-Name enthält …"
              slotProps={{ htmlInput: { 'aria-label': 'Spaces durchsuchen' } }}
              sx={{ mb: 1.5 }}
            />
          )}
          <Box
            component="ul"
            sx={{ m: 0, p: 0, listStyle: 'none', borderTop: 1, borderColor: 'divider' }}
          >
            {matching.length === 0 && (
              <Typography component="li" sx={{ py: 1.25, fontSize: 13.5, color: 'text.secondary' }}>
                Kein Space entspricht dieser Suche.
              </Typography>
            )}
            {matching.map((association) => {
              const origin = provenance(association)
              return (
                <Box
                  component="li"
                  key={association.spaceId}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 1.5,
                    py: 0.75,
                    minHeight: 48,
                    borderBottom: 1,
                    borderColor: 'divider',
                  }}
                >
                  <Box sx={{ minWidth: 0 }}>
                    <Typography
                      noWrap
                      title={association.spaceName}
                      sx={{ fontSize: 13.5, fontWeight: 600 }}
                    >
                      {association.spaceName}
                    </Typography>
                    {(origin || association.narrowerReaderCircle) && (
                      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                        {origin}
                        {origin && association.narrowerReaderCircle && ' · '}
                        {association.narrowerReaderCircle && (
                          <Tooltip
                            title={`Mindestens ein Mitglied dieses Space hat keinen eigenen Lesezugriff auf diese ${noun}.`}
                          >
                            <Box component="span" tabIndex={0}>
                              <StateDot tone="warning" label="nicht alle Mitglieder lesen" />
                            </Box>
                          </Tooltip>
                        )}
                      </Typography>
                    )}
                  </Box>
                  {mayDetach(association.spaceId) && (
                    <RowActions
                      spaceName={association.spaceName}
                      onDetach={() => void handleDetach(association.spaceId, association.spaceName)}
                    />
                  )}
                </Box>
              )
            })}
            {/* The number is there on purpose: it says that the list is incomplete without naming
               a single private space. */}
            {hiddenCount > 0 && (
              <Typography component="li" variant="body2" sx={{ color: 'text.secondary', pt: 0.5 }}>
                {hiddenNote}
              </Typography>
            )}
          </Box>
        </>
      )}
    </Box>
  )
}
