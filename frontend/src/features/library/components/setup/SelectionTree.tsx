import { useCallback, useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import SelectionList, { VIRTUAL_FROM, type CheckState, type SelectionItem, type SelectionNoun } from './SelectionList'
import SelectedSummary from './SelectedSummary'
import { useDelayedFlag } from './useDelayedFlag'

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

export interface TreeListing {
  items: TreeNode[]
  /** Why the listing may be incomplete, shown above it. */
  note?: string | null
}

interface LevelState {
  status: 'loading' | 'ok' | 'error'
  listing?: TreeListing
  error?: string
}

/** Levels shown inline under the view root before the tree moves into the opened node. */
const INLINE_DEPTH = 2
const ROOT = '\u0000root'

interface SelectionTreeProps {
  /** Loads the children of a node, the top level for null. */
  loadChildren: (node: TreeNode | null, path: TreeNode[]) => Promise<TreeListing>
  selection: TreeSelection[]
  onChange: (next: TreeSelection[]) => void
  /** Accessible name of the tree, e.g. „Ordner“. */
  label: string
  noun: SelectionNoun
  /** Name of the top level in the breadcrumb. */
  rootLabel: string
  /** Accessible name of the button that opens a node. */
  openLabel?: (node: TreeNode, depth: number) => string
  /** Text when the top level is empty. */
  emptyText: string
  /** Nodes open from the start, e.g. the synthetic root of a folder tree. */
  initiallyExpanded?: TreeNode[]
}

/**
 * The inline tree for hierarchical sources (sites → libraries → folders, buckets → prefixes,
 * folder trees). Each level loads when it is opened and shows loading, error and empty in place;
 * a level is a {@link SelectionList}, so it grows the same way. Choosing a node covers everything
 * below it; a node with chosen descendants shows as partly chosen. Past two inline levels, or in
 * a level with a hundred entries, opening moves the view into the node with a breadcrumb back.
 */
export default function SelectionTree({
  loadChildren,
  selection,
  onChange,
  label,
  noun,
  rootLabel,
  openLabel = (node) => `Unterordner von „${node.label}“ anzeigen`,
  emptyText,
  initiallyExpanded = [],
}: SelectionTreeProps) {
  const [levels, setLevels] = useState<Record<string, LevelState>>({})
  const [expanded, setExpanded] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(initiallyExpanded.map((node) => [node.id, true])),
  )
  const [viewPath, setViewPath] = useState<TreeNode[]>([])

  const load = useCallback(
    (node: TreeNode | null, path: TreeNode[]) => {
      const key = node?.id ?? ROOT
      setLevels((prev) => ({ ...prev, [key]: { status: 'loading' } }))
      loadChildren(node, path).then(
        (listing) => setLevels((prev) => ({ ...prev, [key]: { status: 'ok', listing } })),
        (err: unknown) =>
          setLevels((prev) => ({
            ...prev,
            [key]: {
              status: 'error',
              error: err instanceof Error && err.message ? err.message : `${noun.many} ließen sich nicht laden.`,
            },
          })),
      )
    },
    [loadChildren, noun.many],
  )

  const viewRoot = viewPath.length > 0 ? viewPath[viewPath.length - 1] : null
  const viewKey = viewRoot?.id ?? ROOT
  useEffect(() => {
    if (!levels[viewKey]) load(viewRoot, viewPath.slice(0, -1))
    for (const node of initiallyExpanded) if (!levels[node.id]) load(node, [])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewKey])

  const chosen = new Set(selection.map((entry) => entry.id))
  const below = new Set(selection.flatMap((entry) => entry.ancestors.map((a) => a.id)))
  const stateOf = (id: string): CheckState =>
    chosen.has(id) ? 'checked' : below.has(id) ? 'mixed' : 'unchecked'

  function toggle(node: TreeNode, path: TreeNode[], checked: boolean): TreeSelection[] {
    return toggleIn(selection, node, path, checked)
  }

  function open(node: TreeNode, path: TreeNode[], drill: boolean) {
    if (drill) {
      setViewPath([...path, node])
      return
    }
    setExpanded((prev) => ({ ...prev, [node.id]: !prev[node.id] }))
    if (!levels[node.id]) load(node, path)
  }

  function renderLevel(parent: TreeNode | null, path: TreeNode[], depth: number) {
    const key = parent?.id ?? ROOT
    const level = levels[key]
    const covered = path.some((node) => chosen.has(node.id))
    return (
      <LevelView
        key={key}
        level={level}
        depth={depth}
        noun={noun}
        onRetry={() => load(parent, path)}
      >
        {level?.listing && (
          <SelectionList
            items={level.listing.items}
            label={parent ? `${label} in „${parent.label}“` : label}
            noun={noun}
            stateOf={stateOf}
            lockedOf={() => covered}
            selectable={(item) => (item as TreeNode).selectable !== false}
            selectedCount={selection.length}
            onToggle={(item, checked) => onChange(toggle(item as TreeNode, path, checked))}
            onToggleMany={(items, checked) =>
              onChange(items.reduce((acc, item) => toggleIn(acc, item as TreeNode, path, checked), selection))
            }
            emptyText={depth === 0 && path.length === 0 ? emptyText : `Keine ${noun.many} darunter.`}
            renderAction={(item) => {
              const node = item as TreeNode
              if (node.leaf) return null
              const drill = depth + 1 >= INLINE_DEPTH || level.listing!.items.length >= VIRTUAL_FROM
              const isOpen = !drill && Boolean(expanded[node.id])
              const name = openLabel(node, path.length)
              return (
                <Tooltip title={drill ? 'Ebene öffnen' : isOpen ? 'Zuklappen' : 'Aufklappen'}>
                  <IconButton
                    size="small"
                    aria-label={name}
                    aria-expanded={drill ? undefined : isOpen}
                    onClick={() => open(node, path, drill)}
                  >
                    {isOpen ? <ExpandMoreIcon fontSize="small" /> : <ChevronRightIcon fontSize="small" />}
                  </IconButton>
                </Tooltip>
              )
            }}
            renderBelow={(item) => {
              const node = item as TreeNode
              if (!expanded[node.id] || depth + 1 >= INLINE_DEPTH) return null
              return (
                <Box sx={{ pl: 3, pr: 1, pb: 1 }}>{renderLevel(node, [...path, node], depth + 1)}</Box>
              )
            }}
          />
        )}
      </LevelView>
    )
  }

  return (
    <Box>
      {viewPath.length > 0 && (
        <Box
          component="nav"
          aria-label={`Ebene in ${label}`}
          sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 0.5, mb: 1, fontSize: 13 }}
        >
          <Link component="button" type="button" onClick={() => setViewPath([])} sx={{ fontSize: 13 }}>
            {rootLabel}
          </Link>
          {viewPath.map((node, index) => (
            <Box key={node.id} sx={{ display: 'contents' }}>
              <Box component="span" aria-hidden sx={{ color: 'text.secondary' }}>
                ›
              </Box>
              {index === viewPath.length - 1 ? (
                <Box
                  component="span"
                  aria-current="location"
                  sx={{ fontWeight: 500, maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  title={node.label}
                >
                  {node.label}
                </Box>
              ) : (
                <Link
                  component="button"
                  type="button"
                  onClick={() => setViewPath(viewPath.slice(0, index + 1))}
                  sx={{ fontSize: 13, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  title={node.label}
                >
                  {node.label}
                </Link>
              )}
            </Box>
          ))}
        </Box>
      )}
      {renderLevel(viewRoot, viewPath, 0)}
      <SelectedSummary
        entries={selection.map((entry) => ({
          id: entry.id,
          label: [...entry.ancestors.map((a) => a.label), entry.label].join(' › '),
        }))}
        noun={noun}
        onRemove={(id) => onChange(selection.filter((entry) => entry.id !== id))}
      />
    </Box>
  )
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

function LevelView({
  level,
  depth,
  noun,
  onRetry,
  children,
}: {
  level: LevelState | undefined
  depth: number
  noun: SelectionNoun
  onRetry: () => void
  children: React.ReactNode
}) {
  const showLoading = useDelayedFlag(!level || level.status === 'loading')
  if (!level || level.status === 'loading') {
    return showLoading ? (
      <Typography role="status" sx={{ fontSize: 13, color: 'text.secondary', py: 1, pl: depth ? 1 : 0 }}>
        {noun.many} werden geladen …
      </Typography>
    ) : (
      <Box sx={{ minHeight: 24 }} />
    )
  }
  if (level.status === 'error') {
    return (
      <Box sx={{ py: 1 }} role="alert">
        <Typography sx={{ fontSize: 13, color: 'error.main', overflowWrap: 'anywhere' }}>{level.error}</Typography>
        <Button size="small" variant="text" sx={{ ml: -1 }} onClick={onRetry}>
          Erneut versuchen
        </Button>
      </Box>
    )
  }
  return (
    <Box>
      {level.listing?.note && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>{level.listing.note}</Typography>
      )}
      {children}
    </Box>
  )
}
