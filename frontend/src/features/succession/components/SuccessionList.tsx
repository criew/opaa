import { useCallback, useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Pagination from '@mui/material/Pagination'
import Typography from '@mui/material/Typography'
import type { SuccessionListResponse } from '@/shared/api/types'
import { getSuccessionEntries } from '../api/successionApi'
import SuccessionRow from './SuccessionRow'
import type { SuccessionTabText } from './successionPresentation'
import EmptyState from '@/shared/ui/states/EmptyState'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

const PAGE_SIZE = 50

/**
 * One tab of the list (ADR-0036, Entscheidung 6): complete from the first day, oldest entry
 * first, entered through the object. **No sorting, filtering or counting by a former owner or an
 * acting person** (Personalrat E1/Z7) - the API deliberately offers none, and the interface does
 * not rebuild it on the client.
 */
export default function SuccessionList({
  text,
  onChanged,
}: {
  text: SuccessionTabText
  /** After a transfer or a note - the page refreshes its counts. */
  onChanged: () => void
}) {
  const [page, setPage] = useState(0)
  const [data, setData] = useState<SuccessionListResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  const load = useCallback(
    () =>
      getSuccessionEntries(text.kind, page, PAGE_SIZE)
        .then((loaded) => {
          setData(loaded)
          setError(null)
        })
        .catch((err: unknown) =>
          setError(err instanceof Error ? err.message : 'Die Liste konnte nicht geladen werden.'),
        )
        .finally(() => setIsLoading(false)),
    [text.kind, page],
  )

  useEffect(() => {
    void load()
  }, [load])

  if (error) {
    return (
      <LoadError
        message="Die Liste konnte nicht geladen werden."
        detail={error}
        onRetry={() => void load()}
      />
    )
  }
  if (isLoading && !data) return <LoadingState />
  if (!data || data.totalElements === 0) {
    // Nothing open: say so, and say when something would show up here.
    return <EmptyState message={`Zurzeit ist hier nichts offen. ${text.emptyExample}`} />
  }

  return (
    <Box>
      <Typography sx={{ fontSize: 14, maxWidth: 760 }}>{text.intro}</Typography>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1, mb: 1.5 }}>
        {data.totalElements === 1 ? '1 Eintrag' : `${data.totalElements} Einträge`}, die ältesten
        zuerst.
      </Typography>

      {/* An empty follow-up page is no „all in order": between two runs a page can empty, and
          the pagination stays the way back. */}
      {data.entries.length === 0 ? (
        <Typography sx={{ color: 'text.secondary' }}>
          Diese Seite ist inzwischen leer — die Liste hat {data.totalElements} Einträge.
        </Typography>
      ) : (
        <Box
          component="ul"
          sx={{
            listStyle: 'none',
            p: 0,
            m: 0,
            borderTop: 1,
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          {data.entries.map((entry) => (
            <SuccessionRow
              key={`${entry.objectType}-${entry.objectId}`}
              kind={text.kind}
              entry={entry}
              onChanged={() => {
                void load()
                onChanged()
              }}
            />
          ))}
        </Box>
      )}

      {(data.totalPages > 1 || data.page > 0) && (
        <Pagination
          sx={{ mt: 2 }}
          count={Math.max(data.totalPages, data.page + 1)}
          page={data.page + 1}
          onChange={(_event, next) => setPage(next - 1)}
          aria-label="Seiten der Liste"
        />
      )}
    </Box>
  )
}
