import { useId, useRef } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import UploadFileOutlinedIcon from '@mui/icons-material/UploadFileOutlined'
import { fontSize } from '@/shared/theme/tokens'

export const TEMPLATE_ACCEPT =
  '.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document'

/** Picks one Word file; the backend checks format, macros and placeholders on upload. */
export default function TemplateFilePicker({
  file,
  onFileChange,
  label = 'Word-Datei auswählen',
  disabled = false,
}: {
  file: File | null
  onFileChange: (file: File | null) => void
  label?: string
  disabled?: boolean
}) {
  const input = useRef<HTMLInputElement>(null)
  const hintId = useId()
  return (
    <Box>
      <input
        ref={input}
        type="file"
        accept={TEMPLATE_ACCEPT}
        hidden
        aria-label={label}
        onChange={(event) => {
          onFileChange(event.target.files?.[0] ?? null)
          event.target.value = ''
        }}
      />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
        <Button
          variant="outlined"
          startIcon={<UploadFileOutlinedIcon />}
          onClick={() => input.current?.click()}
          disabled={disabled}
          aria-describedby={hintId}
        >
          {label}
        </Button>
        <Typography sx={{ fontSize: fontSize.text }}>
          {file ? file.name : 'Keine Datei gewählt'}
        </Typography>
      </Box>
      <Typography id={hintId} sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 1 }}>
        Word-Dokument (.docx) bis 10 MB, ohne Makros. Platzhalter schreiben Sie als{' '}
        <code>{'{{ name }}'}</code>, Listen als <code>{'{% for e in liste %} … {% endfor %}'}</code>{' '}
        und Bedingungen als <code>{'{% if wert %} … {% endif %}'}</code>.
      </Typography>
    </Box>
  )
}
