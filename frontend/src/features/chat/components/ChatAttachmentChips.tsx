import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AttachFileIcon from '@mui/icons-material/AttachFile'
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined'
import type { ChatAttachmentRef } from '@/shared/api/types'
import { CHAT_MAX_WIDTH } from '@/shared/theme/theme'
import { formatFileSize } from '@/shared/utils/labels'
import type { DraftAttachment } from '../hooks/useChatAttachmentDraft'

function phaseText(item: DraftAttachment): string {
  if (item.phase === 'uploading') return 'wird hochgeladen'
  if (item.phase === 'processing') return 'wird gelesen'
  if (item.phase === 'failed') return item.error ?? 'nicht nutzbar'
  return formatFileSize(item.sizeBytes)
}

interface DraftChipsProps {
  items: DraftAttachment[]
  notice: string | null
  onRemove: (key: string) => void
  disabled?: boolean
}

/** The attachments of the question being written, above the input row. */
export function ChatAttachmentDraftChips({ items, notice, onRemove, disabled }: DraftChipsProps) {
  if (items.length === 0 && !notice) return null
  return (
    <Box
      component="ul"
      aria-label="Anhänge der Frage"
      sx={{
        maxWidth: CHAT_MAX_WIDTH,
        mx: 'auto',
        mb: 1,
        p: 0,
        listStyle: 'none',
        display: 'flex',
        flexWrap: 'wrap',
        gap: 0.75,
      }}
    >
      {items.map((item) => {
        const text = phaseText(item)
        const busy = item.phase === 'uploading' || item.phase === 'processing'
        const failed = item.phase === 'failed'
        return (
          <Box component="li" key={item.key} sx={{ maxWidth: '100%' }}>
            <Tooltip title={failed ? text : ''}>
              <Chip
                size="small"
                variant="outlined"
                icon={
                  busy ? (
                    <CircularProgress size={12} aria-hidden />
                  ) : failed ? (
                    <ReportProblemOutlinedIcon aria-hidden sx={{ color: 'error.main' }} />
                  ) : (
                    <AttachFileIcon aria-hidden />
                  )
                }
                label={
                  <>
                    {item.fileName}
                    <Box component="span" sx={{ color: 'text.secondary' }}>
                      {` · ${text}`}
                    </Box>
                  </>
                }
                onDelete={disabled ? undefined : () => onRemove(item.key)}
                aria-label={`Anhang ${item.fileName}, ${text}${disabled ? '' : ', entfernen'}`}
                sx={{ maxWidth: '100%' }}
              />
            </Tooltip>
          </Box>
        )
      })}
      {notice && (
        <Box component="li">
          <Typography role="status" sx={{ fontSize: 12, color: 'text.secondary', py: 0.25 }}>
            {notice}
          </Typography>
        </Box>
      )}
    </Box>
  )
}

/** The attachments a sent question carried, shown above its bubble. */
export function ChatAttachmentRefs({
  attachments,
  onOpen,
}: {
  attachments: ChatAttachmentRef[]
  onOpen?: (attachment: ChatAttachmentRef) => void
}) {
  if (attachments.length === 0) return null
  return (
    <Box
      component="ul"
      aria-label="Anhänge"
      data-testid="message-attachments"
      sx={{
        display: 'flex',
        flexWrap: 'wrap',
        justifyContent: 'flex-end',
        gap: 0.5,
        m: 0,
        mb: 0.5,
        p: 0,
        listStyle: 'none',
      }}
    >
      {attachments.map((attachment) => (
        <Box component="li" key={attachment.id} sx={{ maxWidth: '100%' }}>
          <Chip
            size="small"
            variant="outlined"
            icon={<AttachFileIcon aria-hidden />}
            label={attachment.fileName}
            onClick={onOpen ? () => onOpen(attachment) : undefined}
            aria-label={onOpen ? `Anhang ${attachment.fileName} herunterladen` : undefined}
            sx={{ maxWidth: '100%' }}
          />
        </Box>
      ))}
    </Box>
  )
}
