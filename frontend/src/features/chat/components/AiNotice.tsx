import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import { fontSize, iconSize, readingWidth } from '@/shared/theme/tokens'

/** The accessible name of the notice region; screen readers announce it before the text. */
const NOTICE_NAME = 'Hinweis zur KI-Nutzung'

/**
 * The authority's notice that answers come from an AI (Art. 50 AI Act), in the wording the
 * branding carries. The store always holds a text - the OPAA standard until the branding arrives
 * or when its request fails - so the notice cannot be off. `variant="prominent"` is the empty
 * chat's notice before the first question, `variant="line"` the quiet line under the input from
 * the first question on - one notice per surface.
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
        // the full text stays in the DOM (screen readers, title); the line itself shows two rows
        title={notice}
        sx={{
          fontSize: fontSize.meta,
          color: 'text.secondary',
          display: '-webkit-box',
          WebkitBoxOrient: 'vertical',
          WebkitLineClamp: 2,
          overflow: 'hidden',
        }}
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
        maxWidth: readingWidth,
        px: 2,
        py: 0.5,
        borderLeft: 3,
        borderColor: 'divider',
        color: 'text.primary',
        textAlign: 'left',
      }}
    >
      <SmartToyOutlinedIcon
        aria-hidden
        sx={{ fontSize: iconSize.md, mt: '1px', color: 'text.secondary' }}
      />
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
        fontSize: fontSize.meta,
        fontWeight: 500,
        color: 'text.secondary',
        whiteSpace: 'nowrap',
      }}
    >
      <SmartToyOutlinedIcon aria-hidden sx={{ fontSize: iconSize.xs }} />
      KI-erzeugt
    </Typography>
  )
}
