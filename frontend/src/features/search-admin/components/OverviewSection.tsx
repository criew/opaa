import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import PageSection from '@/shared/ui/PageSection'
import KeyValueList from '@/shared/ui/KeyValueList'
import StatusLine, { type StatusTone } from '@/shared/ui/StatusLine'
import type { SearchModelRoleStatusResponse } from '@/shared/api/types'
import { useSearchAdminStore } from '../stores/searchAdminStore'
import {
  PATH_LABELS,
  PATH_STATE_LABELS,
  ROLE_LABELS,
  ROLE_STATE_LABELS,
} from '../utils/searchAdminLabels'

/**
 * A model role as a block, not as a card: the state is in the dot and the word, the
 * two details below it in a key-value list. An error stays an alert - that is
 * a message, not resting content.
 */
function ModelRoleCard({ role }: { role: SearchModelRoleStatusResponse }) {
  const tone: StatusTone = role.faulted ? 'error' : role.state === 'ACTIVE' ? 'success' : 'neutral'
  return (
    <Box>
      <StatusLine
        headline={`${ROLE_LABELS[role.role]} — ${ROLE_STATE_LABELS[role.state]}`}
        tone={tone}
        detail={role.faulted ? undefined : role.detail}
      >
        {role.faulted && (
          <Alert severity="error" sx={{ mb: 1 }}>
            {role.detail}
          </Alert>
        )}
        <KeyValueList
          valueFont="mono"
          entries={[
            { label: 'Endpunkt', value: role.endpoint ?? 'nicht hinterlegt' },
            { label: 'Modell-Kennung', value: role.modelIdentifier ?? 'nicht hinterlegt' },
          ]}
        />
      </StatusLine>
    </Box>
  )
}

/** Model roles and search paths: does the search run at all, and which of its paths are active. */
export default function OverviewSection() {
  const status = useSearchAdminStore((s) => s.status)
  const statusError = useSearchAdminStore((s) => s.statusError)
  const loadStatus = useSearchAdminStore((s) => s.loadStatus)

  useEffect(() => {
    void loadStatus()
  }, [loadStatus])

  return (
    <>
      {statusError && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {statusError}
        </Alert>
      )}

      <PageSection title="Modellrollen">
        {/* Stacked, not side by side: every role now carries two details below its
           row, and three wrapping columns would be offset instead of flush. */}
        <Stack spacing={2.5}>
          {status?.modelRoles.map((role) => (
            <ModelRoleCard key={role.role} role={role} />
          ))}
        </Stack>
      </PageSection>

      <PageSection title="Suchpfade">
        <Stack spacing={1.5}>
          {status?.searchPaths.map((path) => (
            <StatusLine
              key={path.path}
              headline={`${PATH_LABELS[path.path]} — ${PATH_STATE_LABELS[path.state]}`}
              tone={path.state === 'ACTIVE' ? 'success' : 'warning'}
              detail={path.detail}
            />
          ))}
        </Stack>
      </PageSection>
    </>
  )
}
