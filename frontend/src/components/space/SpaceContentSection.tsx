import { useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useSpaceStore } from '../../stores/spaceStore'
import { notify } from '../../stores/notificationStore'
import AssetTilePicker from '../assets/AssetTilePicker'
import { assetPickKey, type AssetPick } from '../assets/assetPick'
import { successionAwareMessage } from '../succession/successionConflict'
import SectionHead from '../SectionHead'

/** The one disclosure about unreadable associations: no number, no name (ADR-0039). */
export const NOT_ALL_READABLE = 'Nicht alle zugeordneten Inhalte sind für Sie lesbar.'

interface SpaceContentSectionProps {
  spaceId: string
  /** A curator, an admin or the owner checks and unchecks; everybody else only looks on. */
  canManage: boolean
}

/**
 * The tab "Inhalte" of the space settings: every asset type in one tile list, a check mark meaning
 * "associated with this space". A check mark takes effect at once; a failed request springs it
 * back. The association grants nobody access
 * (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren).
 */
export default function SpaceContentSection({ spaceId, canManage }: SpaceContentSectionProps) {
  const storeError = useSpaceStore((s) => s.error)
  const associations = useSpaceStore((s) => s.assetAssociations)
  const loadedSpaceId = useSpaceStore((s) => s.assetAssociationsSpaceId)
  const hasUnreadable = useSpaceStore((s) => s.hasUnreadableAssociations)
  const isLoading = useSpaceStore((s) => s.isLoadingAssetAssociations)
  const loadAssetAssociations = useSpaceStore((s) => s.loadAssetAssociations)
  const associateAsset = useSpaceStore((s) => s.associateAsset)
  const detachAsset = useSpaceStore((s) => s.detachAsset)
  // The state a pending request is heading for: the pick when associating, null when detaching.
  const [heading, setHeading] = useState<ReadonlyMap<string, AssetPick | null>>(new Map())
  // Catches a second click before the first one's state has rendered; `disabled` would drop focus.
  const pending = useRef(new Set<string>())

  useEffect(() => {
    void loadAssetAssociations(spaceId)
  }, [loadAssetAssociations, spaceId])

  const value = useMemo(() => {
    const settled = associations
      .filter((association) => !heading.has(assetPickKey(association)))
      .map(({ assetType, assetId, name, description }) => ({
        assetType,
        assetId,
        name,
        description: description ?? null,
      }))
    const headingIn = [...heading.values()].filter((pick): pick is AssetPick => pick !== null)
    return [...settled, ...headingIn]
  }, [associations, heading])

  const busyKeys = useMemo(() => new Set(heading.keys()), [heading])

  function withHeading(key: string, target: AssetPick | null | undefined) {
    setHeading((current) => {
      const next = new Map(current)
      if (target === undefined) next.delete(key)
      else next.set(key, target)
      return next
    })
  }

  async function setAssociated(pick: AssetPick, associated: boolean) {
    const key = assetPickKey(pick)
    if (pending.current.has(key)) return
    pending.current.add(key)
    withHeading(key, associated ? pick : null)
    let failure: string | null = null
    try {
      if (associated) await associateAsset(spaceId, pick.assetType, pick.assetId)
      else await detachAsset(spaceId, pick.assetId)
    } catch (err) {
      failure = successionAwareMessage(
        err,
        associated ? 'Zuordnung fehlgeschlagen' : 'Lösen fehlgeschlagen',
      )
    }
    pending.current.delete(key)
    withHeading(key, undefined)
    if (failure) {
      notify(failure, 'error')
    } else if (associated) {
      notify(`„${pick.name}“ zugeordnet.`, 'success')
    } else {
      notify(`„${pick.name}“ gelöst.`, 'info', {
        label: 'Rückgängig',
        onClick: () => void setAssociated(pick, true),
      })
    }
  }

  function handleChange(next: AssetPick[]) {
    const nextKeys = new Set(next.map(assetPickKey))
    const currentKeys = new Set(value.map(assetPickKey))
    next
      .filter((pick) => !currentKeys.has(assetPickKey(pick)))
      .forEach((pick) => void setAssociated(pick, true))
    value
      .filter((pick) => !nextKeys.has(assetPickKey(pick)))
      .forEach((pick) => void setAssociated(pick, false))
  }

  const loaded = loadedSpaceId === spaceId && !isLoading

  return (
    <Stack spacing={2}>
      {/* Die h2 dieses Panels unter der h1 der Seite. */}
      <SectionHead>Inhalte</SectionHead>
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        Ein Chat in diesem Space nutzt nur, was hier ausgewählt ist.
      </Typography>
      {storeError && <Alert severity="error">{storeError}</Alert>}
      {hasUnreadable && loaded && <Alert severity="info">{NOT_ALL_READABLE}</Alert>}
      <AssetTilePicker
        // Another space starts afresh: the narrowing keeps every tile it has shown.
        key={spaceId}
        value={value}
        onChange={handleChange}
        chosenOnlyLabel="Nur zugeordnete"
        readOnly={!canManage}
        busyKeys={busyKeys}
        showSummary={false}
        noneChosenText={
          !loaded
            ? storeError
              ? null
              : 'Inhalte werden geladen …'
            : // With unreadable associations, "nothing associated" would be false; the hint says it.
              hasUnreadable
              ? null
              : canManage
                ? 'Diesem Space ist noch nichts zugeordnet. Schalten Sie „Nur zugeordnete“ aus, um Inhalte auszuwählen.'
                : 'Diesem Space ist noch nichts zugeordnet.'
        }
        aria-label="Inhalte dieses Space"
      />
    </Stack>
  )
}
