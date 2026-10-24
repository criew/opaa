import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import { useBrandingStore } from '@/shared/branding/brandingStore'

/** The accessible name of the notice region; screen readers announce it before the text. */
const NOTICE_NAME = 'Hinweis zur KI-Nutzung'

/**
 * The authority's notice that answers come from an AI (Art. 50 AI Act), in the wording the
 * branding carries. The store always holds a text - the OPAA standard until the branding arrives
 * or when its request fails - so the notice cannot be off. `variant="prominent"` is the empty
 * chat's notice before the first question, `variant="line"` the quiet permanent line under the
 * input.
 */
export function AiNotice({ variant }: { variant: 'prominent' | 'line' }) {
  const notice = useBrandingStore((s) => s.branding.aiNotice)

  if (variant === 'line') {
    return (
      <Typography
        component="div"
        role="note"
        aria-label={NOTICE_NAME}
        data-testid="ai-notice-line"
        sx={{ fontSize: 12, color: 'text.secondary' }}
      >
        {notice}
      </Typography>
    )
  }

  return (
    <Box
      role="note"
      aria-label={NOTICE_NAME}
      data-testid="ai-notice"
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 1,
        maxWidth: 520,
        px: 2,
        py: 0.5,
        borderLeft: 3,
        borderColor: 'divider',
        color: 'text.primary',
        textAlign: 'left',
      }}
    >
      <SmartToyOutlinedIcon aria-hidden sx={{ fontSize: 20, mt: '1px', color: 'text.secondary' }} />
      <Typography variant="body2">{notice}</Typography>
    </Box>
  )
}

/** The mark at the foot of every answer; set by the role alone, so older chats carry it too. */
export function AiAnswerMark() {
  return (
    <Typography
      component="span"
      role="note"
      aria-label="Diese Antwort wurde von einer KI erzeugt"
      data-testid="ai-answer-mark"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.5,
        fontSize: 12,
        fontWeight: 500,
        color: 'text.secondary',
        whiteSpace: 'nowrap',
      }}
    >
      <SmartToyOutlinedIcon aria-hidden sx={{ fontSize: 14 }} />
      KI-erzeugt
    </Typography>
  )
}
