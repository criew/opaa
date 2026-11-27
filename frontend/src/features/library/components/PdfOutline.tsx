import OutlineTree from './documentView/OutlineTree'

/** One entry of a PDF's outline: its title, where it points, its sub-entries. */
export interface OutlineNode {
  title: string
  dest: string | unknown[] | null
  /** The 1-based page the entry points at, `null` when its destination does not resolve. */
  page: number | null
  children: OutlineNode[]
}

interface PdfOutlineProps {
  nodes: OutlineNode[]
  /** The entry of the section on screen, marked as the current one - or its folded parent. */
  activeNode: OutlineNode | null
  onSelect: (node: OutlineNode) => void
}

/** A PDF's outline as a tree; an entry whose destination is missing cannot be chosen. */
export default function PdfOutline(props: PdfOutlineProps) {
  return <OutlineTree {...props} isDisabled={(node) => node.dest == null} />
}
