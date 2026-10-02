import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import { Link as RouterLink } from 'react-router'
import { spaceSettingsRoute } from '../../routes'
import type { SpaceRole } from '../../types/api'
import {
  NO_KNOWLEDGE_ASSIGNED,
  NO_KNOWLEDGE_READABLE,
  canAssignKnowledge,
  type SpaceKnowledgeGap,
} from './spaceKnowledge'

interface SpaceKnowledgeNoticeProps {
  spaceId: string
  gap: SpaceKnowledgeGap
  /** The caller's role in the space - decides between the direct link and the hint who can. */
  role: SpaceRole | string | undefined
}

/**
 * The visible hint that a space's chat searches no knowledge, so an empty space never answers
 * silently. Nothing assigned: a direct link to the association for those who may assign, a
 * hint who can for everyone else. Nothing readable: no link - assigning more would not help.
 */
export default function SpaceKnowledgeNotice({ spaceId, gap, role }: SpaceKnowledgeNoticeProps) {
  if (gap === 'none-readable') {
    return (
      <Alert severity="info" data-testid="space-knowledge-notice">
        {NO_KNOWLEDGE_READABLE}
      </Alert>
    )
  }
  const mayAssign = canAssignKnowledge(role)
  return (
    <Alert
      severity="warning"
      data-testid="space-knowledge-notice"
      action={
        mayAssign ? (
          <Button
            component={RouterLink}
            to={spaceSettingsRoute(spaceId, 'knowledge')}
            color="inherit"
            size="small"
          >
            Wissen zuordnen
          </Button>
        ) : undefined
      }
    >
      {NO_KNOWLEDGE_ASSIGNED}{' '}
      {mayAssign
        ? 'Antworten im Chat stützen sich erst auf Dokumente, wenn Sie Wissen zuordnen.'
        : 'Wissen ordnen die Kuratoren und Administratoren dieses Space zu.'}
    </Alert>
  )
}
