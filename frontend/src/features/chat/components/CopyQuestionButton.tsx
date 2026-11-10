import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import CheckIcon from '@mui/icons-material/Check'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import { useClipboardCopy } from './useClipboardCopy'
import { iconSize } from '@/shared/theme/tokens'

/** Class the question row uses to reveal the button on hover and keyboard focus. */
export const QUESTION_COPY_CLASS = 'question-copy'

/**
 * Copies one of the person's own questions as plain text - to ask it again, slightly changed, in
 * another chat. Quiet until the question is hovered or focused; on touch screens always shown.
 */
export default function CopyQuestionButton({ text }: { text: string }) {
  const { copied, copy } = useClipboardCopy('Die Frage')
  return (
    <Tooltip title={copied ? 'Kopiert' : 'Frage kopieren'}>
      <IconButton
        size="small"
        className={QUESTION_COPY_CLASS}
        aria-label={copied ? 'Frage kopiert' : 'Frage kopieren'}
        onClick={() => void copy(text)}
        sx={{ color: copied ? 'primary.main' : 'text.secondary', p: 0.5 }}
      >
        {copied ? (
          <CheckIcon aria-hidden sx={{ fontSize: iconSize.sm }} />
        ) : (
          <ContentCopyOutlinedIcon aria-hidden sx={{ fontSize: iconSize.sm }} />
        )}
      </IconButton>
    </Tooltip>
  )
}
