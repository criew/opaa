import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import DocumentScannerOutlinedIcon from '@mui/icons-material/DocumentScannerOutlined'
import { RECOGNIZED_TEXT_NOTICE } from '@/shared/utils/recognizedText'

interface RecognizedTextNoticeProps {
  /** Font size of the line; the icon follows it. */
  fontSize?: number
}

/**
 * The quiet mark of a passage read by text recognition: a scanner icon and the sentence in
 * `fg-2`. The sentence carries the meaning, so screen readers read it as plain text and the icon
 * stays hidden from them.
 */
export default function RecognizedTextNotice({ fontSize = 11.5 }: RecognizedTextNoticeProps) {
  return (
    <Box
      component="span"
      data-testid="recognized-text-notice"
      sx={{ display: 'flex', alignItems: 'center', gap: 0.5, mt: 0.25 }}
    >
      <DocumentScannerOutlinedIcon
        aria-hidden
        sx={{ fontSize: fontSize + 1.5, color: 'text.secondary', flex: 'none' }}
      />
      <Typography component="span" sx={{ fontSize, color: 'text.secondary' }}>
        {RECOGNIZED_TEXT_NOTICE}
      </Typography>
    </Box>
  )
}
