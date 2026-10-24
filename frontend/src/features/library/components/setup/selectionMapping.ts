import type { SelectionItem } from './SelectionList'

export interface TreeNode extends SelectionItem {
  /** False for a node that only groups (a SharePoint site); default true. */
  selectable?: boolean
  /** A node known to have no children. */
  leaf?: boolean
}

/** One chosen node with the path that leads to it, root first. */
export interface TreeSelection {
  id: string
  label: string
  ancestors: TreeNode[]
}

export const FOLDER_ROOT: TreeNode = { id: '/', label: 'Alle Ordner' }

/** The folders of the form (one path per line) as tree selections, ancestors from the path. */
export function foldersToSelection(text: string): TreeSelection[] {
  const folders = [
    ...new Set(
      text
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    ),
  ]
  return folders.map((folder) => {
    if (folder === '/') return { id: '/', label: FOLDER_ROOT.label, ancestors: [] }
    const segments = folder.split('/').filter(Boolean)
    const ancestors: TreeNode[] = [FOLDER_ROOT]
    segments.slice(0, -1).forEach((segment, index) => {
      ancestors.push({ id: `/${segments.slice(0, index + 1).join('/')}`, label: segment })
    })
    return { id: `/${segments.join('/')}`, label: segments[segments.length - 1], ancestors }
  })
}

export function selectionToFolders(selection: TreeSelection[]): string {
  return selection.map((entry) => entry.id).join('\n')
}

/** `selection` with `node` chosen (replacing what lies below it) or removed. */
export function toggleIn(
  selection: TreeSelection[],
  node: TreeNode,
  path: TreeNode[],
  checked: boolean,
): TreeSelection[] {
  const rest = selection.filter((entry) => entry.id !== node.id)
  if (!checked) return rest
  return [
    ...rest.filter((entry) => !entry.ancestors.some((a) => a.id === node.id)),
    { id: node.id, label: node.label, ancestors: path },
  ]
}
