import {
  useCallback,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

export interface AssetHeadlineEditorProps {
  /** The saved name; the editor keeps its own draft until „Speichern". */
  name: string
  description?: string | null
  /** Prefix of the field ids, unique per page. */
  idPrefix: string
  /** Label of the name field, e.g. „Name der Bibliothek". */
  nameLabel: string
  /** Names the pencil for assistive tech, e.g. „Name und Beschreibung der Bibliothek bearbeiten". */
  editLabel: string
  canEdit: boolean
  /** Rejections are shown in the head itself; the draft survives them. */
  onSave: (name: string, description: string) => Promise<void>
  /** Badges on the heading's baseline - role, source type, administrative bypass. */
  badges?: ReactNode
  /** Leaves the pencil out; editing then starts through `editRequest`. */
  hidePencil?: boolean
  /** Every change of this counter opens the form once, e.g. from a menu entry. */
  editRequest?: number
}

/**
 * Name and description of an asset in the head of its detail page: read as a heading, with
 * a pencil that turns the two into a small form. Never click-into-the-text — the mode is explicit,
 * so a screen reader and a keyboard reach it the same way.
 *
 * <p>Focus follows the mode (docs/design/accessibility.md, 2.1/2.4): the name field takes it when
 * the form opens, the pencil gets it back on every exit — Escape, „Abbrechen" and a successful
 * save — and a refused save moves it onto the message, which keeps the draft.
 */
export default function AssetHeadlineEditor({
  name,
  description,
  idPrefix,
  nameLabel,
  editLabel,
  canEdit,
  onSave,
  badges,
  hidePencil = false,
  editRequest = 0,
}: AssetHeadlineEditorProps) {
  const [draft, setDraft] = useState<{ name: string; description: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const editButtonRef = useRef<HTMLButtonElement>(null)
  // Returning the focus to the pencil has to wait for the button to exist again: it is unmounted
  // while the form stands, so a stable callback ref focuses it the moment it is mounted back.
  const returnFocus = useRef(false)

  const focusOnMount = useCallback((node: HTMLInputElement | null) => {
    node?.focus()
  }, [])

  const focusErrorOnMount = useCallback((node: HTMLDivElement | null) => {
    node?.focus()
  }, [])

  const acceptPencil = useCallback((node: HTMLButtonElement | null) => {
    editButtonRef.current = node
    if (node && returnFocus.current) {
      returnFocus.current = false
      node.focus()
    }
  }, [])

  function startEditing() {
    setError(null)
    setDraft({ name, description: description ?? '' })
  }

  // each new value of `editRequest` opens the form once, as the pencil would
  const [seenRequest, setSeenRequest] = useState(editRequest)
  if (editRequest !== seenRequest) {
    setSeenRequest(editRequest)
    if (canEdit) startEditing()
  }

  function closeEditing() {
    returnFocus.current = true
    setError(null)
    setDraft(null)
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!draft || !draft.name.trim()) return
    setError(null)
    setSaving(true)
    try {
      await onSave(draft.name, draft.description)
      closeEditing()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  function handleKeyDown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.stopPropagation()
      closeEditing()
    }
  }

  const headingRow = (
    // as high with the pencil as without it: a reader's head ends where an editor's does
    <Stack
      direction="row"
      spacing={1.5}
      useFlexGap
      sx={{
        alignItems: 'center',
        flexWrap: 'nowrap',
        mb: 0.5,
        minHeight: { xs: 28, sm: 34 },
        '& > h1': { flex: '0 1 auto', minWidth: 0 },
      }}
    >
      {draft ? <PageHeading title={name} visuallyHidden /> : <PageHeading title={name} />}
      {badges}
      {canEdit && !draft && !hidePencil && (
        <Tooltip title={editLabel}>
          <IconButton ref={acceptPencil} size="small" aria-label={editLabel} onClick={startEditing}>
            <EditOutlinedIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
    </Stack>
  )

  if (!draft) {
    return (
      // a long compound breaks instead of running out of the head; from 600 px on the title takes
      // at most two lines, the full name stays in the tooltip and for screen readers
      <Box
        title={name}
        sx={{
          minWidth: 0,
          '& h1': {
            overflowWrap: 'anywhere',
            hyphens: 'auto',
            // smaller below 600 px, never cut there (P43); from 600 px on at most two lines
            '@media (max-width: 599.95px)': { fontSize: 14, lineHeight: '17px' },
            '@media (min-width: 600px)': {
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
            },
          },
        }}
      >
        {headingRow}
        {/* one line, reserved even when empty: the head keeps its height for every asset */}
        <Typography
          title={description ?? undefined}
          sx={{
            fontSize: 13.5,
            lineHeight: { xs: '20px', sm: '22px' },
            minHeight: { xs: 20, sm: 22 },
            color: 'text.secondary',
            maxWidth: 640,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {description}
        </Typography>
      </Box>
    )
  }

  return (
    <Box
      component="form"
      onSubmit={(event: FormEvent) => void submit(event)}
      onKeyDown={handleKeyDown}
      sx={{ minWidth: 0, maxWidth: 640, width: '100%' }}
    >
      {/* The badges stay in place in edit mode as well; only the heading becomes
         invisible, so that the page is never left without its h1. */}
      {headingRow}
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
      <Box sx={{ mb: 1.5 }}>
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
      <Stack direction="row" spacing={1}>
        <Button
          type="submit"
          variant="contained"
          size="small"
          disabled={saving || !draft.name.trim()}
        >
          {saving ? 'Wird gespeichert …' : 'Speichern'}
        </Button>
        <Button size="small" onClick={closeEditing} disabled={saving}>
          Abbrechen
        </Button>
      </Stack>
    </Box>
  )
}
