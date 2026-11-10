import { fontSize } from '@/shared/theme/tokens'
import { useRef, useState, type FormEvent, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import ValueRow from '@/shared/ui/ValueRow'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import ErrorSummary, { type SummaryEntry } from '@/shared/ui/feedback/ErrorSummary'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'

export interface RowErrors {
  /** Messages by field, as the backend or the row's own check named them. */
  fields: Record<string, string>
  /** One message that names no field. */
  general: string | null
}

/**
 * One row of the read raster - title | content | „Ändern“ - that opens its own small form below
 * the content. A failed save shows every problem in a summary that takes the focus („Fehler: …“);
 * the fields show the same messages. Without the right to change, the row has no action.
 */
export default function EditableRow({
  idPrefix,
  title,
  value,
  canEdit,
  fieldLabels,
  onOpen,
  onSave,
  editor,
}: {
  idPrefix: string
  title: string
  value: ReactNode
  canEdit: boolean
  /** The field names the summary may mention, with the label the form shows for them. */
  fieldLabels: Record<string, string>
  /** Resets the draft from the saved state before the form opens. */
  onOpen: () => void
  /** Saves the draft; throws an API error or returns local field messages to show. */
  onSave: () => Promise<Record<string, string> | void>
  editor: (errors: Record<string, string>) => ReactNode
}) {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [errors, setErrors] = useState<RowErrors>({ fields: {}, general: null })
  const summaryRef = useRef<HTMLDivElement>(null)
  const actionRef = useRef<HTMLButtonElement>(null)

  function close() {
    setOpen(false)
    setErrors({ fields: {}, general: null })
    requestAnimationFrame(() => actionRef.current?.focus())
  }

  function fail(next: RowErrors) {
    setErrors(next)
    requestAnimationFrame(() => summaryRef.current?.focus())
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      const local = await onSave()
      if (local && Object.keys(local).length > 0) {
        fail({ fields: local, general: null })
        return
      }
      close()
    } catch (err) {
      const fields: Record<string, string> = {}
      for (const error of apiFieldErrors(err)) {
        if (!(error.field in fields)) fields[error.field] = error.message
      }
      fail({
        fields,
        general:
          Object.keys(fields).length === 0
            ? err instanceof Error
              ? err.message
              : 'Speichern fehlgeschlagen.'
            : null,
      })
    } finally {
      setSaving(false)
    }
  }

  const summary: SummaryEntry[] = Object.entries(errors.fields).map(([field, message]) => ({
    text: `${fieldLabels[field] ?? title}: ${message}`,
    onJump: () =>
      document
        .getElementById(`${idPrefix}-${field}`)
        ?.querySelector<HTMLElement>('input, textarea, button')
        ?.focus(),
  }))

  return (
    <ValueRow
      title={title}
      value={value}
      action={
        canEdit && !open ? (
          <Link
            ref={actionRef}
            component="button"
            type="button"
            onClick={() => {
              onOpen()
              setOpen(true)
            }}
            aria-label={`${title} ändern`}
            sx={{ fontSize: fontSize.text }}
          >
            Ändern
          </Link>
        ) : undefined
      }
    >
      {open && (
        <Box component="form" noValidate onSubmit={handleSubmit} sx={{ pt: 1 }}>
          <ErrorSummary ref={summaryRef} entries={summary} idPrefix={idPrefix} />
          {errors.general && (
            <Alert ref={summaryRef} tabIndex={-1} severity="error" sx={{ mb: 2 }}>
              Fehler: {errors.general}
            </Alert>
          )}
          {editor(errors.fields)}
          <Box sx={{ display: 'flex', gap: 1, mt: 2 }}>
            <BusyButton
              type="submit"
              variant="contained"
              size="small"
              busy={saving}
              busyAnnouncement="Wird gespeichert"
            >
              Speichern
            </BusyButton>
            <Button size="small" onClick={close} disabled={saving}>
              Abbrechen
            </Button>
          </Box>
        </Box>
      )}
    </ValueRow>
  )
}
