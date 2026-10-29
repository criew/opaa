import { useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import IconButton from '@mui/material/IconButton'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'

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
  /** The entry of the section on screen, marked as the current one. */
  activeNode: OutlineNode | null
  onSelect: (node: OutlineNode) => void
  depth?: number
}

/** A PDF's outline as a tree: top-level entries open, deeper levels folded until expanded. */
export default function PdfOutline({ nodes, activeNode, onSelect, depth = 0 }: PdfOutlineProps) {
  return (
    <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
      {nodes.map((node, index) => (
        <OutlineEntry
          key={index}
          node={node}
          activeNode={activeNode}
          onSelect={onSelect}
          depth={depth}
        />
      ))}
    </Box>
  )
}

function OutlineEntry({
  node,
  activeNode,
  onSelect,
  depth,
}: {
  node: OutlineNode
  activeNode: OutlineNode | null
  onSelect: (node: OutlineNode) => void
  depth: number
}) {
  const active = node === activeNode
  const [expanded, setExpanded] = useState(depth === 0)
  const hasChildren = node.children.length > 0
  return (
    <Box component="li">
      <Box sx={{ display: 'flex', alignItems: 'flex-start', pl: depth * 1.5 }}>
        {hasChildren ? (
          <IconButton
            size="small"
            aria-label={expanded ? `${node.title} zuklappen` : `${node.title} aufklappen`}
            aria-expanded={expanded}
            onClick={() => setExpanded((open) => !open)}
            sx={{ p: 0.25 }}
          >
            {expanded ? <ExpandMoreIcon fontSize="small" /> : <ChevronRightIcon fontSize="small" />}
          </IconButton>
        ) : (
          <Box sx={{ width: 24, flex: 'none' }} />
        )}
        <ButtonBase
          onClick={() => onSelect(node)}
          disabled={node.dest == null}
          aria-current={active ? 'location' : undefined}
          sx={{
            fontWeight: active ? 600 : 400,
            bgcolor: active ? 'action.selected' : undefined,
            justifyContent: 'flex-start',
            textAlign: 'left',
            fontSize: 13,
            py: 0.5,
            px: 0.5,
            borderRadius: 1,
            flex: 1,
            '&:hover': { bgcolor: 'action.hover' },
          }}
        >
          {node.title}
        </ButtonBase>
      </Box>
      {hasChildren && expanded && (
        <PdfOutline
          nodes={node.children}
          activeNode={activeNode}
          onSelect={onSelect}
          depth={depth + 1}
        />
      )}
    </Box>
  )
}
