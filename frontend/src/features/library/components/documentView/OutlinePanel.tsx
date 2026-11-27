import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import TocIcon from '@mui/icons-material/Toc'
import { FindingPanel } from './FindingCard'
import OutlineTree, { type OutlineTreeNode } from './OutlineTree'

/** The button that shows and hides the outline beside the document. */
export function OutlineToggle({
  open,
  controls,
  onToggle,
}: {
  open: boolean
  controls: string
  onToggle: () => void
}) {
  return (
    <Tooltip title={open ? 'Gliederung ausblenden' : 'Gliederung einblenden'}>
      <IconButton
        size="small"
        aria-label="Gliederung"
        aria-expanded={open}
        aria-controls={open ? controls : undefined}
        onClick={onToggle}
      >
        <TocIcon fontSize="small" />
      </IconButton>
    </Tooltip>
  )
}

/** The outline as a panel of the reading column, a navigation landmark named „Gliederung“. */
export function OutlinePanel<T extends OutlineTreeNode<T>>({
  id,
  nodes,
  activeNode,
  onSelect,
}: {
  id: string
  nodes: T[]
  activeNode: T | null
  onSelect: (node: T) => void
}) {
  return (
    <FindingPanel>
      <Box id={id} component="nav" aria-label="Gliederung" sx={{ mx: -1 }}>
        <OutlineTree nodes={nodes} activeNode={activeNode} onSelect={onSelect} />
      </Box>
    </FindingPanel>
  )
}

/** The row above the document that holds the outline's toggle. */
export function OutlineToolbar({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        px: { xs: 2, sm: 3 },
        pb: 1,
        flex: 'none',
      }}
    >
      {children}
    </Box>
  )
}
