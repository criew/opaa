import { useId, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Collapse from '@mui/material/Collapse'
import Typography from '@mui/material/Typography'
import RuleOutlinedIcon from '@mui/icons-material/RuleOutlined'

interface SpaceInstructionProps {
  /** The instruction of the chat's space to the model. */
  text: string
}

/**
 * Shows in the chat's header that an instruction of the space takes part in every answer, and lets
 * every member read it: a button and a collapsible panel, collapsed by default. Read-only here; the
 * space settings are where the space role ADMIN changes it.
 */
export default function SpaceInstruction({ text }: SpaceInstructionProps) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const toggleRef = useRef<HTMLButtonElement>(null)

  function handleEscape(event: { key: string }) {
    if (event.key !== 'Escape' || !open) return
    setOpen(false)
    toggleRef.current?.focus()
  }

  return (
    <>
      <Button
        ref={toggleRef}
        size="small"
        variant="outlined"
        startIcon={<RuleOutlinedIcon aria-hidden />}
        onClick={() => setOpen((previous) => !previous)}
        onKeyDown={handleEscape}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        sx={{ flexShrink: 0 }}
      >
        Anweisung des Space
      </Button>
      <Collapse in={open} unmountOnExit sx={{ flexBasis: '100%', width: '100%', minWidth: 0 }}>
        <Box
          id={panelId}
          role="region"
          aria-label="Anweisung des Space"
          onKeyDown={handleEscape}
          sx={{ pt: 2, pb: 0.5 }}
        >
          <Typography component="h2" sx={{ fontSize: 15, fontWeight: 600 }}>
            Anweisung des Space
          </Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.5 }}>
            Die Verantwortlichen dieses Space haben diese Anweisung hinterlegt. Sie wirkt bei jeder
            Antwort in diesem Space mit; Zitate und Quellenangaben hebt sie nicht auf.
          </Typography>
          <Typography
            variant="body2"
            sx={{ mt: 1.5, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
          >
            {text}
          </Typography>
        </Box>
      </Collapse>
    </>
  )
}
