import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'

interface PlanConfirmationProps {
  agentName: string
  /** Sends the confirmation; the agent starts working off the plan in a new run. */
  onConfirm: () => void
  /** Leaves the change to the person: the input takes the focus with the agent chosen. */
  onChange: () => void
  disabled?: boolean
}

/**
 * The two answers to an agent's plan under the chat's latest answer: confirming it is the one
 * primary action, changing it is secondary and happens in the input, as a new message to the same
 * agent. Confirming locks both buttons at once, so a double click sends one confirmation.
 */
export default function PlanConfirmation({
  agentName,
  onConfirm,
  onChange,
  disabled = false,
}: PlanConfirmationProps) {
  const [confirmed, setConfirmed] = useState(false)
  const locked = disabled || confirmed
  return (
    <Box
      role="group"
      aria-label={`Plan von ${agentName}`}
      data-testid="plan-confirmation"
      sx={{ display: 'flex', gap: 1, pl: 7, pr: 2, mb: 2 }}
    >
      <Button
        variant="contained"
        size="small"
        onClick={() => {
          setConfirmed(true)
          onConfirm()
        }}
        disabled={locked}
      >
        Bestätigen
      </Button>
      <Button variant="outlined" size="small" onClick={onChange} disabled={locked}>
        Ändern
      </Button>
    </Box>
  )
}
