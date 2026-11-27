import { useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import IconButton from '@mui/material/IconButton'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import { fontSize } from '@/shared/theme/tokens'

/** The shape every outline entry shares: its title and its sub-entries. */
export interface OutlineTreeNode<T> {
  title: string
  children: T[]
}

interface OutlineTreeProps<T extends OutlineTreeNode<T>> {
  nodes: T[]
  /** The entry of the section on screen, marked as the current one - or its folded parent. */
  activeNode: T | null
  onSelect: (node: T) => void
  /** An entry that leads nowhere stays visible but cannot be chosen. */
  isDisabled?: (node: T) => boolean
  depth?: number
}

/**
 * A document's outline as a tree: top-level entries open, deeper levels folded until expanded.
 * Shared by the PDF view (its bookmarks) and the text views (their headings).
 */
export default function OutlineTree<T extends OutlineTreeNode<T>>({
  nodes,
  activeNode,
  onSelect,
  isDisabled,
  depth = 0,
}: OutlineTreeProps<T>) {
  return (
    <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
      {nodes.map((node, index) => (
        <OutlineEntry
          key={index}
          node={node}
          activeNode={activeNode}
          onSelect={onSelect}
          isDisabled={isDisabled}
          depth={depth}
        />
      ))}
    </Box>
  )
}

function OutlineEntry<T extends OutlineTreeNode<T>>({
  node,
  activeNode,
  onSelect,
  isDisabled,
  depth,
}: {
  node: T
  activeNode: T | null
  onSelect: (node: T) => void
  isDisabled?: (node: T) => boolean
  depth: number
}) {
  const [expanded, setExpanded] = useState(depth === 0)
  const hasChildren = node.children.length > 0
  // a folded entry stands for the current one when that lies hidden below it
  const active =
    node === activeNode || (!expanded && activeNode !== null && holds(node.children, activeNode))
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
          disabled={isDisabled?.(node) ?? false}
          aria-current={active ? 'location' : undefined}
          sx={{
            fontWeight: active ? 600 : 400,
            bgcolor: active ? 'action.selected' : undefined,
            justifyContent: 'flex-start',
            textAlign: 'left',
            fontSize: fontSize.text,
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
        <OutlineTree
          nodes={node.children}
          activeNode={activeNode}
          onSelect={onSelect}
          isDisabled={isDisabled}
          depth={depth + 1}
        />
      )}
    </Box>
  )
}

function holds<T extends OutlineTreeNode<T>>(nodes: T[], target: T): boolean {
  return nodes.some((node) => node === target || holds(node.children, target))
}
