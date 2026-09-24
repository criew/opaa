import { useMemo, useState } from 'react'
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
import type { PromptResponse, PromptVariable } from '../../types/api'
import { usePromptLibraryStore } from '../../stores/promptLibraryStore'
import { apiFieldErrors } from '../../services/apiErrorDetails'
import {
  PROMPT_DESCRIPTION_MAX_LENGTH,
  PROMPT_NAME_MAX_LENGTH,
  PROMPT_TEXT_MAX_LENGTH,
  PROMPT_TITLE_MAX_LENGTH,
  suggestPromptName,
  validatePromptDraft,
  variablesForText,
} from '../../utils/promptTemplate'
import { fontFamily } from '../../theme/tokens'
import FieldLabel from '../wizard/FieldLabel'
import SectionHead from '../SectionHead'
import PromptTextHighlight from './PromptTextHighlight'
import PromptVariablesTable from './PromptVariablesTable'
import PromptPreview from './PromptPreview'

interface PromptEditorDialogProps {
  open: boolean
  promptLibraryId: string
  /** The prompt to change; `null` creates a new one. */
  prompt: PromptResponse | null
  onClose: () => void
}

function knownVariablesOf(prompt: PromptResponse | null): Record<string, PromptVariable> {
  return Object.fromEntries((prompt?.variables ?? []).map((variable) => [variable.name, variable]))
}

/**
 * Creates or changes one prompt. The variable rows follow the text: writing `{{name}}` adds a
 * definition, removing it drops the definition from what is saved - a definition the text does
 * not use is refused by the server anyway. The client checks run before sending; whatever the
 * server still refuses is shown verbatim, with its field errors.
 */
export default function PromptEditorDialog({
  open,
  promptLibraryId,
  prompt,
  onClose,
}: PromptEditorDialogProps) {
  const savePrompt = usePromptLibraryStore((s) => s.savePrompt)
  const [title, setTitle] = useState(prompt?.title ?? '')
  const [name, setName] = useState(prompt?.name ?? '')
  // A new prompt's command name follows its title until someone types a name of their own.
  const [nameTouched, setNameTouched] = useState(prompt !== null)
  const [description, setDescription] = useState(prompt?.description ?? '')
  const [text, setText] = useState(prompt?.text ?? '')
  const [known, setKnown] = useState<Record<string, PromptVariable>>(() => knownVariablesOf(prompt))
  const [clientErrors, setClientErrors] = useState<string[]>([])
  const [serverErrors, setServerErrors] = useState<string[]>([])
  const [saving, setSaving] = useState(false)

  const variables = useMemo(() => variablesForText(text, known), [text, known])
  const hasPlaceholders = text.includes('{{')

  function changeTitle(value: string) {
    setTitle(value)
    if (!nameTouched) setName(suggestPromptName(value))
  }

  async function handleSave() {
    const draft = { name, title, description, text, variables }
    const errors = validatePromptDraft(draft)
    setClientErrors(errors)
    setServerErrors([])
    if (errors.length > 0) return
    setSaving(true)
    try {
      await savePrompt(promptLibraryId, prompt?.id ?? null, {
        name,
        title: title.trim(),
        description: description.trim() || null,
        text,
        variables,
        sortOrder: prompt?.sortOrder ?? null,
      })
      onClose()
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Der Prompt konnte nicht gespeichert werden'
      const fieldErrors = apiFieldErrors(err).map((fieldError) =>
        fieldError.field ? `${fieldError.field}: ${fieldError.message}` : fieldError.message,
      )
      setServerErrors([message, ...fieldErrors])
    } finally {
      setSaving(false)
    }
  }

  const errors = [...clientErrors, ...serverErrors]

  return (
    <Dialog
      open={open}
      onClose={saving ? undefined : onClose}
      maxWidth="md"
      fullWidth
      aria-labelledby="prompt-editor-title"
    >
      <DialogTitle id="prompt-editor-title">
        {prompt ? 'Prompt bearbeiten' : 'Neuer Prompt'}
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2.5}>
          {errors.length > 0 && (
            <Alert severity="error" role="alert">
              {errors.length === 1 ? (
                errors[0]
              ) : (
                <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
                  {errors.map((error) => (
                    <li key={error}>{error}</li>
                  ))}
                </Box>
              )}
            </Alert>
          )}
          <Box>
            <FieldLabel htmlFor="prompt-editor-title-field">Titel</FieldLabel>
            <TextField
              id="prompt-editor-title-field"
              size="small"
              fullWidth
              value={title}
              onChange={(e) => changeTitle(e.target.value)}
              placeholder="z. B. Anhörungsschreiben"
              slotProps={{ htmlInput: { maxLength: PROMPT_TITLE_MAX_LENGTH } }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="prompt-editor-name">Befehl</FieldLabel>
            <TextField
              id="prompt-editor-name"
              size="small"
              fullWidth
              value={name}
              onChange={(e) => {
                setNameTouched(true)
                setName(e.target.value)
              }}
              placeholder="anhoerung"
              helperText={
                <>
                  Aufruf im Chat:{' '}
                  <Box component="span" sx={{ fontFamily: fontFamily.mono }}>
                    /{name || 'name'}
                  </Box>{' '}
                  — Kleinbuchstaben, Ziffern und einzelne Bindestriche, eindeutig in dieser
                  Prompt-Bibliothek.
                </>
              }
              slotProps={{
                htmlInput: {
                  maxLength: PROMPT_NAME_MAX_LENGTH,
                  sx: { fontFamily: fontFamily.mono },
                },
              }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="prompt-editor-description">Beschreibung (optional)</FieldLabel>
            <TextField
              id="prompt-editor-description"
              size="small"
              fullWidth
              multiline
              minRows={2}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              slotProps={{ htmlInput: { maxLength: PROMPT_DESCRIPTION_MAX_LENGTH } }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="prompt-editor-text">Text</FieldLabel>
            <TextField
              id="prompt-editor-text"
              fullWidth
              multiline
              minRows={5}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="Entwirf ein Anhörungsschreiben zum Aktenzeichen {{aktenzeichen}}, Stand {{CURRENT_DATE}}."
              helperText={`Platzhalter schreiben Sie als {{name}}. {{CURRENT_DATE}} und {{USER_NAME}} füllt OPAA beim Einsetzen selbst. ${text.length} von ${PROMPT_TEXT_MAX_LENGTH} Zeichen.`}
              slotProps={{ htmlInput: { maxLength: PROMPT_TEXT_MAX_LENGTH } }}
            />
            {hasPlaceholders && (
              <Box sx={{ mt: 1 }}>
                <PromptTextHighlight text={text} label="Text mit hervorgehobenen Platzhaltern" />
              </Box>
            )}
          </Box>

          <Box>
            <SectionHead component="h3">Variablen</SectionHead>
            <PromptVariablesTable
              variables={variables}
              onChange={(variable) => setKnown((prev) => ({ ...prev, [variable.name]: variable }))}
            />
          </Box>

          <Box>
            <SectionHead component="h3">Vorschau mit Beispielwerten</SectionHead>
            {text.trim() ? (
              <PromptPreview text={text} variables={variables} />
            ) : (
              <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                Die Vorschau erscheint, sobald der Prompt einen Text hat.
              </Typography>
            )}
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={saving}>
          Abbrechen
        </Button>
        <Button variant="contained" onClick={() => void handleSave()} disabled={saving}>
          {saving ? 'Wird gespeichert …' : 'Prompt speichern'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
