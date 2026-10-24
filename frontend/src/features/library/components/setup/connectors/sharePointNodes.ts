import {
  sharePointFolderLabel,
  sharePointFolderPath,
  sharePointLibraryLabel,
  type SharePointLibraryChoice,
} from '../../../utils/sharePointSource'
import type { PickNode } from '../PickCombo'

export interface SharePointNodeData {
  driveId: string
  /** Absent for the whole document library. */
  folderId?: string
}

export function libraryNode(driveId: string, label: string): PickNode {
  return { key: `drive:${driveId}`, label, trail: [], branch: true, data: { driveId } }
}

export function folderNode(driveId: string, folderId: string, label: string, trail: string[]): PickNode {
  return { key: `folder:${folderId}`, label, trail, branch: true, data: { driveId, folderId } }
}

/** The stored choice as nodes: a whole library, or its folders below it. */
export function sharePointNodes(libraries: SharePointLibraryChoice[]): PickNode[] {
  return libraries.flatMap((library) => {
    const label = sharePointLibraryLabel(library)
    if (library.folders.length === 0) return [libraryNode(library.driveId, label)]
    return library.folders.map((folder) =>
      folderNode(library.driveId, folder.id, sharePointFolderLabel(folder), [label]),
    )
  })
}

/** Nodes back to the stored choice, grouped by document library. */
export function sharePointChoices(nodes: PickNode[]): SharePointLibraryChoice[] {
  const byDrive = new Map<string, SharePointLibraryChoice>()
  for (const node of nodes) {
    const data = node.data as SharePointNodeData
    if (!data.folderId) {
      byDrive.set(data.driveId, { driveId: data.driveId, name: node.label, folders: [] })
      continue
    }
    const existing = byDrive.get(data.driveId) ?? {
      driveId: data.driveId,
      name: node.trail[0] ?? null,
      folders: [],
    }
    existing.folders.push({
      id: data.folderId,
      name: sharePointFolderPath(node.trail.slice(1), node.label),
    })
    byDrive.set(data.driveId, existing)
  }
  return [...byDrive.values()]
}
