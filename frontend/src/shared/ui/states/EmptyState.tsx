import { useRef, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import { TextAction } from '../DetailSection'
import { radius } from '../../theme/tokens'

/** An empty text is a whole sentence with its full stop (guidelines 5.7). */
export type Sentence = `${string}.`

/** The sentence of the `noMatch` state; a list says nothing else when its filter hides every row. */
export const NO_MATCH_MESSAGE = 'Keine Treffer.'

interface Common {
  /** Dashed frame for the place of a list or table; without it the state stands as plain text. */
  framed?: boolean
}

/**
 * The three empty states of guidelines 5.7, told apart by `kind`:
 *
 * - `initial` (default): nothing exists yet. „Noch keine <Objekte>." plus the create action with
 *   the label of the page header; whoever may not create reads who can.
 * - `noMatch`: a search or filter hides every entry. „Keine Treffer." plus „Filter zurücksetzen".
 * - `noAccess`: the content exists but is withheld. What is missing and who is responsible.
 */
export type EmptyStateProps = Common &
  (
    | { kind?: 'initial'; message: Sentence; action?: ReactNode }
    | {
        kind: 'noMatch'
        /** Clears search and filters; the action is always „Filter zurücksetzen". */
        onReset: () => void
        /** Why this filter combination cannot match, where the reason is not obvious. */
        hint?: Sentence
      }
    | { kind: 'noAccess'; message: Sentence }
  )

/** The search field nearest to `from`, the place a reset hands the focus back to. */
function nearestSearchField(from: HTMLElement | null): HTMLElement | null {
  for (let scope = from?.parentElement ?? null; scope; scope = scope.parentElement) {
    const field = scope.querySelector<HTMLElement>('[data-list-search], input[type="search"]')
    if (field) return field
  }
  return null
}

export default function EmptyState(props: EmptyStateProps) {
  const kind = props.kind ?? 'initial'
  const rootRef = useRef<HTMLDivElement>(null)
  const message = props.kind === 'noMatch' ? NO_MATCH_MESSAGE : props.message

  return (
    <Box
      ref={rootRef}
      data-empty-state={kind}
      sx={
        props.framed
          ? {
              border: 1,
              borderStyle: 'dashed',
              borderColor: 'divider',
              borderRadius: `${radius.md}px`,
              p: 3,
              textAlign: 'center',
            }
          : undefined
      }
    >
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center', justifyContent: props.framed ? 'center' : 'flex-start' }}
      >
        {kind === 'noAccess' && (
          <LockOutlinedIcon aria-hidden fontSize="small" sx={{ color: 'text.secondary' }} />
        )}
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {message}
        </Typography>
      </Stack>
      {props.kind === 'noMatch' && props.hint && (
        <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.5 }}>
          {props.hint}
        </Typography>
      )}
      {props.kind === 'noMatch' && (
        <Box sx={{ mt: 1 }}>
          <TextAction
            onClick={() => {
              // Found before the reset: the list that replaces this state may unmount it.
              const field = nearestSearchField(rootRef.current)
              props.onReset()
              field?.focus()
            }}
          >
            Filter zurücksetzen
          </TextAction>
        </Box>
      )}
      {(props.kind === undefined || props.kind === 'initial') && props.action && (
        <Box sx={{ mt: 1.5 }}>{props.action}</Box>
      )}
    </Box>
  )
}
