import { useEffect, useMemo } from 'react'
import { useChatStore } from '../stores/chatStore'
import type { SearchScope } from '../stores/chatSettingsPersistence'
import { useSpaceStore } from '@/features/space'
import { spaceKnowledgeGap } from '@/features/space'
import type { SpaceLibrary } from './chatInputText'

/** One chip of the bar for a referenced library: known, still loading, or no longer available. */
export type LibraryChip =
  | { kind: 'known'; libraryId: string; library: SpaceLibrary }
  | { kind: 'loading'; libraryId: string }
  | { kind: 'missing'; libraryId: string }

/**
 * The libraries the chip bar may offer for the chat's space, the chips of the referenced ones,
 * and what the line under the input says while the space's associations are not known yet.
 */
export function useSpaceLibraryScope(scope: SearchScope, referencedLibraryIds: string[]) {
  // What the chip bar offers mirrors ChatService#effectiveLibraryScope: the space's associated
  // knowledge libraries the person may read, nothing else. Loaded per current chat's space via the
  // same spaceStore SpaceSettingsPage/SpacePage use (their routes never render at the same time as
  // this one) - but that store write-back is asynchronous and per-space, so
  // this component must not simply trust whatever is currently in assetAssociations.
  // assetAssociationsSpaceId names which space that data actually describes;
  // isAssetAssociationsCurrent below is false while it does not match chatSpaceId - covering the
  // load still being in flight, a load that failed (spaceStore leaves it null rather than
  // defaulting to "no associations"), and the moment right after switching to a
  // chat in a different space, before its own load has even started.
  const chatSpaceId = useChatStore((s) => s.spaceId)
  const hasKnowledge = useSpaceStore((s) => s.hasKnowledge)
  const hasReadableKnowledge = useSpaceStore((s) => s.hasReadableKnowledge)
  const assetAssociations = useSpaceStore((s) => s.assetAssociations)
  const assetAssociationsSpaceId = useSpaceStore((s) => s.assetAssociationsSpaceId)
  const loadAssetAssociations = useSpaceStore((s) => s.loadAssetAssociations)
  const spaceRole = useSpaceStore(
    (s) => s.spaces.find((space) => space.id === chatSpaceId)?.userRole,
  )
  const isAssetAssociationsCurrent = assetAssociationsSpaceId === chatSpaceId
  const spaceLibraries = useMemo(
    (): SpaceLibrary[] =>
      isAssetAssociationsCurrent
        ? assetAssociations
            .filter((a) => a.assetType === 'KNOWLEDGE_LIBRARY')
            .map((a) => ({ id: a.assetId, name: a.name ?? '' }))
        : [],
    [assetAssociations, isAssetAssociationsCurrent],
  )
  const knowledgeGap =
    chatSpaceId && isAssetAssociationsCurrent
      ? spaceKnowledgeGap(hasKnowledge, hasReadableKnowledge)
      : null

  useEffect(() => {
    if (chatSpaceId) {
      void loadAssetAssociations(chatSpaceId)
    }
  }, [chatSpaceId, loadAssetAssociations])

  // One entry per referenced id, in the order the ids were added - not per matched library, so a
  // reference that is (still, or no longer) missing from the space's libraries keeps its own chip
  // instead of silently disappearing. An empty-looking bar for scope 'libraries' would otherwise
  // be indistinguishable from a deliberately emptied one.
  const libraryChips = useMemo(() => {
    if (scope !== 'libraries') return []
    return referencedLibraryIds.map((libraryId) => {
      const library = spaceLibraries.find((l) => l.id === libraryId)
      if (library) return { kind: 'known' as const, libraryId, library }
      if (!isAssetAssociationsCurrent) return { kind: 'loading' as const, libraryId }
      // Known, and still not found - no longer associated, no longer readable or deleted.
      // Removable like any other chip, so a stale reference does not get stuck in the bar.
      return { kind: 'missing' as const, libraryId }
    })
  }, [spaceLibraries, isAssetAssociationsCurrent, referencedLibraryIds, scope])

  // While the space's associations are not known yet (still loading, or the load failed), the
  // @Space-Wissen chip must not quietly promise a scope; the line under the input says so. An
  // empty scope is stated by SpaceKnowledgeNotice above the chip bar.
  const scopeNotice =
    scope === 'all' && !isAssetAssociationsCurrent ? 'Suchbereich wird ermittelt …' : null

  return { chatSpaceId, spaceRole, spaceLibraries, knowledgeGap, libraryChips, scopeNotice }
}
