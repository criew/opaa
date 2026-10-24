import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { SourceBrowseResponse } from '@/shared/api/types'
import SelectionList, { type SelectionItem, type SelectionNoun } from './SelectionList'
import SelectedSummary from './SelectedSummary'
import { listingNote } from './probe'
import { useDelayedFlag } from './useDelayedFlag'
import LoadError from './LoadError'

interface FlatScopeProps {
  /** Loads the whole list once. */
  load: () => Promise<SourceBrowseResponse>
  toItem: (entry: SourceBrowseResponse['entries'][number]) => SelectionItem | null
  selected: SelectionItem[]
  onChange: (next: SelectionItem[]) => void
  label: string
  noun: SelectionNoun
  emptyText: string
}

/**
 * A flat list of source objects (Confluence spaces, Drive areas) on the shared
 * {@link SelectionList}, with loading, error and incomplete states in place.
 */
export default function FlatScope({
  load,
  toItem,
  selected,
  onChange,
  label,
  noun,
  emptyText,
}: FlatScopeProps) {
  const [state, setState] = useState<{
    status: 'loading' | 'ok' | 'error'
    items?: SelectionItem[]
    note?: string | null
    error?: string
  }>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let live = true
    load().then(
      (response) => {
        if (!live) return
        setState({
          status: 'ok',
          items: response.entries
            .map(toItem)
            .filter((item): item is SelectionItem => item !== null),
          note: listingNote(response),
        })
      },
      (err: unknown) => {
        if (!live) return
        setState({
          status: 'error',
          error:
            err instanceof Error && err.message
              ? err.message
              : `${noun.many} ließen sich nicht laden.`,
        })
      },
    )
    return () => {
      live = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt])
  const showLoading = useDelayedFlag(state.status === 'loading')
  const chosen = new Set(selected.map((item) => item.id))
  return (
    <Box>
      {state.status === 'loading' &&
        (showLoading ? (
          <Typography role="status" sx={{ fontSize: 13, color: 'text.secondary' }}>
            {noun.many} werden geladen …
          </Typography>
        ) : (
          <Box sx={{ minHeight: 24 }} />
        ))}
      {state.status === 'error' && (
        <LoadError
          message={state.error ?? ''}
          onRetry={() => {
            setState({ status: 'loading' })
            setAttempt(attempt + 1)
          }}
        />
      )}
      {state.status === 'ok' && state.items && (
        <>
          {state.note && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>
              {state.note}
            </Typography>
          )}
          <SelectionList
            items={state.items}
            label={label}
            noun={noun}
            stateOf={(id) => (chosen.has(id) ? 'checked' : 'unchecked')}
            selectedCount={selected.length}
            onToggle={(item, checked) =>
              onChange(
                checked ? [...selected, item] : selected.filter((entry) => entry.id !== item.id),
              )
            }
            onToggleMany={(items, checked) => {
              const ids = new Set(items.map((item) => item.id))
              onChange(
                checked
                  ? [...selected, ...items.filter((item) => !chosen.has(item.id))]
                  : selected.filter((entry) => !ids.has(entry.id)),
              )
            }}
            emptyText={emptyText}
          />
        </>
      )}
      {(state.items?.length ?? 0) >= 7 || state.status !== 'ok' ? (
        <SelectedSummary
          entries={selected}
          noun={noun}
          onRemove={(id) => onChange(selected.filter((entry) => entry.id !== id))}
        />
      ) : null}
    </Box>
  )
}
