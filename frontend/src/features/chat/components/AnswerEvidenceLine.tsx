import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlined'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlineOutlined'
import RemoveCircleOutlineIcon from '@mui/icons-material/RemoveCircleOutlineOutlined'
import type { AnswerEvidence } from '@/shared/api/types'
import { describeAnswerEvidence } from './answerEvidenceText'

const LEVEL_STYLE = {
  SUPPORTED: { Icon: CheckCircleOutlineIcon, color: 'success.main' },
  PARTIALLY_SUPPORTED: { Icon: ErrorOutlineIcon, color: 'warning.main' },
  UNSUPPORTED: { Icon: RemoveCircleOutlineIcon, color: 'text.secondary' },
} as const

/**
 * The Belegstand under an answer: symbol, level word and reason. The level is always readable as
 * a word, so colour never carries it alone.
 */
export default function AnswerEvidenceLine({ evidence }: { evidence: AnswerEvidence }) {
  const { label, reason } = describeAnswerEvidence(evidence)
  const { Icon, color } = LEVEL_STYLE[evidence.level]
  return (
    <Typography
      component="div"
      variant="body2"
      data-testid="answer-evidence"
      data-level={evidence.level}
      sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.75, mt: 1.5, fontSize: 12.5 }}
    >
      <Icon aria-hidden sx={{ fontSize: 16, mt: '1px', color }} />
      <Box component="span">
        <Box component="span" sx={visuallyHidden}>
          Belegstand:{' '}
        </Box>
        <Box component="span" sx={{ fontWeight: 600, color: 'text.primary' }}>
          {label}
        </Box>
        <Box component="span" sx={{ color: 'text.secondary' }}>
          {' · '}
          {reason}
        </Box>
      </Box>
    </Typography>
  )
}
