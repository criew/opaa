import type { PickNode } from '../PickCombo'
import { counted } from './common'

export const ALL_FOLDERS: PickNode = { key: '/', label: '/ (alles)', trail: [] }

/** The folders of a form, one path per line, as nodes of the picker. */
export function folderNodes(text: string): PickNode[] {
  const seen = new Set<string>()
  const nodes: PickNode[] = []
  for (const line of text.split('\n')) {
    const path = line.trim()
    if (path === '' || seen.has(path)) continue
    seen.add(path)
    if (path === '/') {
      nodes.push(ALL_FOLDERS)
      continue
    }
    const segments = path.split('/').filter(Boolean)
    nodes.push({
      key: path.startsWith('/') ? path : `/${path}`,
      label: segments[segments.length - 1] ?? path,
      trail: segments.slice(0, -1),
      branch: true,
    })
  }
  return nodes
}

export function folderText(nodes: PickNode[]): string {
  return nodes.map((node) => node.key).join('\n')
}

/** „/ (alles)“ or „3 Ordner“. */
export function folderSummary(text: string): string | null {
  const nodes = folderNodes(text)
  if (nodes.length === 0) return null
  if (nodes.length === 1 && nodes[0].key === '/') return '/ (alles)'
  if (nodes.length === 1) return nodes[0].key
  return counted(nodes.length, ['Ordner', 'Ordner'])
}

