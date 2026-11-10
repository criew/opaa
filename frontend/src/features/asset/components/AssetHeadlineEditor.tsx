import { useCallback, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { fontSize } from '@/shared/theme/tokens'

export interface AssetHeadlineProps {
  /** Prefix of the field ids, unique per page. */
  idPrefix: string
  /** Label of the name field, e.g. „Name der Bibliothek". */
  nameLabel: string
  /** The „⋯"-Eintrag and the dialog title, e.g. „Name und Beschreibung ändern". */
  editLabel: string
  canEdit: boolean
  /** Rejections are shown in the dialog itself; the draft survives them. */
  onSave: (name: string, description: string) => Promise<void>
}

/**
 * Name and description of an asset in the head of its detail page, read-only: the name as the
 * page's heading, the description on one fixed line below it. The change runs through the head's
 * „⋯" (ADR-0039: one surface, one way); {@link EditHeadlineDialog} is that way.
 */
export default function AssetHeadline({
  name,
  description,
}: {
  name: string
  description?: string | null
}) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Stack
        direction="row"
        spacing={1.5}
        sx={{
          alignItems: { xs: 'flex-start', sm: 'center' },
          mb: 0.5,
          minHeight: 34,
          position: 'relative',
        }}
      >
        {/* a long compound breaks at a syllable instead of running out at 320 px */}
        <Box title={name} sx={{ minWidth: 0 }}>
          {/* one line at 1280 px with the full name in the tooltip; below 600 px the whole name
              over as many lines as it needs, in a stage above the running text */}
          <PageHeading
            title={name}
            sx={{
              overflow: 'hidden',
              hyphens: 'auto',
              overflowWrap: 'break-word',
              fontSize: { xs: fontSize.title, sm: fontSize.detailTitle },
              lineHeight: { xs: '22px', sm: '28px' },
              display: { xs: 'block', sm: '-webkit-box' },
              WebkitBoxOrient: 'vertical',
              WebkitLineClamp: { xs: 'none', sm: 1 },
            }}
          />
        </Box>
      </Stack>
      {/* one line, kept even when empty: the figures below never move */}
      <Typography
        noWrap
        title={description ?? undefined}
        sx={{ fontSize: fontSize.text, lineHeight: '20px', minHeight: 20, color: 'text.secondary' }}
      >
        {description}
      </Typography>
    </Box>
  )
}

/**
 * The change behind the head's „⋯": name and description in a small dialog. Focus moves onto the
 * name field when it opens (docs/design/accessibility.md, 2.1); a refused save keeps the draft and
 * moves the focus onto the message. MUI returns the focus to the „⋯" trigger on close.
 */
export function EditHeadlineDialog({
  open,
  onClose,
  name,
  description,
  idPrefix,
  nameLabel,
  editLabel,
  onSave,
}: {
  open: boolean
  onClose: () => void
  name: string
  description?: string | null
} & Pick<AssetHeadlineProps, 'idPrefix' | 'nameLabel' | 'editLabel' | 'onSave'>) {
  const [draft, setDraft] = useState({ name, description: description ?? '' })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const focusOnMount = useCallback((node: HTMLInputElement | null) => {
    node?.focus()
  }, [])

  const focusErrorOnMount = useCallback((node: HTMLDivElement | null) => {
    node?.focus()
  }, [])

  // A fresh draft every time the dialog opens, so an earlier cancellation never lingers and a
  // change made elsewhere is picked up.
  function handleEnter() {
    setDraft({ name, description: description ?? '' })
    setError(null)
  }

  function handleClose() {
    if (saving) return
    onClose()
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!draft.name.trim()) return
    setError(null)
    setSaving(true)
    try {
      await onSave(draft.name, draft.description)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="sm"
      fullWidth
      slotProps={{ transition: { onEnter: handleEnter } }}
    >
      <DialogTitle>{editLabel}</DialogTitle>
      <Box component="form" onSubmit={(event: FormEvent) => void submit(event)}>
        <DialogContent>
          {error && (
            <Alert
              severity="error"
              ref={focusErrorOnMount}
              tabIndex={-1}
              sx={{ mb: 1.5 }}
              onClose={() => setError(null)}
            >
              {error}
            </Alert>
          )}
          <Box sx={{ mb: 1.5 }}>
            <FieldLabel htmlFor={`${idPrefix}-name`}>{nameLabel}</FieldLabel>
            <TextField
              id={`${idPrefix}-name`}
              size="small"
              fullWidth
              inputRef={focusOnMount}
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              slotProps={{ htmlInput: { maxLength: 255 } }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor={`${idPrefix}-description`}>Beschreibung (optional)</FieldLabel>
            <TextField
              id={`${idPrefix}-description`}
              size="small"
              fullWidth
              multiline
              minRows={2}
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              slotProps={{ htmlInput: { maxLength: 2000 } }}
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleClose} disabled={saving}>
            Abbrechen
          </Button>
          <Button type="submit" variant="contained" disabled={saving || !draft.name.trim()}>
            {saving ? 'Wird gespeichert …' : 'Speichern'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  )
}
