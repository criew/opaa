import { useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import { useSpaceStore } from '../../stores/spaceStore'
import {
  notify,
  useNotificationStore,
  type NotificationSeverity,
} from '../../stores/notificationStore'
import AssetTilePicker from '../assets/AssetTilePicker'
import { assetPickKey, type AssetPick } from '../assets/assetPick'
import { successionAwareMessage } from '../succession/successionConflict'

/** The one disclosure about unreadable associations: no number, no name (ADR-0039). */
export const NOT_ALL_READABLE = 'Nicht alle zugeordneten Inhalte sind für Sie lesbar.'

const NO_ASSOCIATIONS: never[] = []

interface SpaceContentSectionProps {
  spaceId: string
  /** A curator, an admin or the owner checks and unchecks; everybody else only looks on. */
  canManage: boolean
}

/**
 * The tab "Inhalte" of the space settings: every asset type in one tile list, a check mark meaning
 * "associated with this space". A check mark takes effect at once; a failed request springs it
 * back. The association grants nobody access
 * (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren). Mounted per space: its
 * pending changes, notices and remembered tiles never carry over to another one.
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
  // Only the latest outcome is worth reading: an older confirmation of this tab gives way at once,
  // an error stays until it is read.
  const lastNotice = useRef<{ id: number; severity: NotificationSeverity } | null>(null)
  const container = useRef<HTMLDivElement>(null)

  useEffect(() => {
    void loadAssetAssociations(spaceId)
  }, [loadAssetAssociations, spaceId])

  // An undo offered here belongs to this space; leaving it withdraws the offer.
  useEffect(
    () => () => {
      const notice = lastNotice.current
      if (notice && notice.severity !== 'error') {
        useNotificationStore.getState().dismiss(notice.id)
      }
      lastNotice.current = null
    },
    [spaceId],
  )

  // The store may still hold another space's associations until this space's have loaded.
  const own = loadedSpaceId === spaceId ? associations : NO_ASSOCIATIONS

  const value = useMemo(() => {
    const settled = own
      .filter((association) => !heading.has(assetPickKey(association)))
      .map(({ assetType, assetId, name, description }) => ({
        assetType,
        assetId,
        name,
        description: description ?? null,
      }))
    const headingIn = [...heading.values()].filter((pick): pick is AssetPick => pick !== null)
    return [...settled, ...headingIn]
  }, [own, heading])

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
    const previous = lastNotice.current
    if (previous && previous.severity !== 'error') {
      useNotificationStore.getState().dismiss(previous.id)
    }
    const severity: NotificationSeverity = failure ? 'error' : associated ? 'success' : 'info'
    const id = failure
      ? notify(failure, severity)
      : associated
        ? notify(`„${pick.name}“ zugeordnet.`, severity)
        : notify(`„${pick.name}“ gelöst.`, severity, {
            label: 'Rückgängig',
            onClick: () => {
              focusTile(key)
              void setAssociated(pick, true)
            },
          })
    lastNotice.current = { id, severity }
  }

  /** Returns the focus to a tile, e.g. after the popup that held it has gone. */
  function focusTile(key: string) {
    Array.from(container.current?.querySelectorAll<HTMLElement>('[data-asset-tile]') ?? [])
      .find((element) => element.getAttribute('data-asset-tile') === key)
      ?.focus()
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
    <Stack spacing={2} ref={container}>
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
