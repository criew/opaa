import type { EmbeddingInfoResponse } from '@/shared/api/types'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'

interface EmbeddingInfoTableProps {
  info: EmbeddingInfoResponse
}

/**
 * The installed embedding model in the same list form as the chat models, without a row menu:
 * a change goes through the guided change below the table, never through an edit in place.
 *
 * A table for a single row is typographically the weaker choice; here recognition weighs more.
 * Whoever switches between the tabs sees the same kind of thing — a model with its details.
 */
export default function EmbeddingInfoTable({ info }: EmbeddingInfoTableProps) {
  return (
    <AdminList
      label="Einbettungsmodell"
      rows={[info]}
      rowKey={(row) => row.model ?? 'embedding'}
      rowLabel={(row) => row.model ?? 'Einbettungsmodell'}
      minWidth={640}
      columns={[
        { id: 'model', head: 'Modell', cell: (row) => <ListText mono>{row.model}</ListText> },
        {
          id: 'endpoint',
          head: 'Endpunkt',
          cell: (row) => <ListText mono>{row.baseUrl ?? '—'}</ListText>,
        },
        {
          id: 'dimensions',
          head: 'Dimensionen',
          width: '14%',
          cell: (row) => <ListText mono>{row.dimensions}</ListText>,
        },
        {
          id: 'maxInput',
          head: 'Eingabegrenze',
          width: '16%',
          cell: (row) => <ListText mono>{row.maxInputTokens ?? '—'}</ListText>,
        },
        {
          id: 'key',
          head: 'Schlüssel',
          width: '14%',
          cell: (row) => (row.apiKeySet ? 'gesetzt' : 'keiner'),
        },
      ]}
    />
  )
}
