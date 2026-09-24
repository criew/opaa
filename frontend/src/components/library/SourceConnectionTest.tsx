import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import type { DocumentSourceType, SourceConnectionTestResponse } from '../../types/api'
import { testLibrarySource } from '../../services/api'
import {
  deriveLibrarySourceConfigPayload,
  validateLibrarySourceFields,
  type GenericSourceValues,
} from '../../utils/librarySourceConfig'

interface SourceConnectionTestProps {
  sourceType: DocumentSourceType
  values: GenericSourceValues
  /**
   * Edit mode: lets the probe fall back to the library's stored credentials and checks the MANAGER
   * bar on that library instead of the Anlegerecht (#1856).
   */
  libraryId?: string
  size?: 'small' | 'medium'
}

interface Outcome {
  /** The probe this outcome belongs to - a since-changed one hides it again. */
  token: string
  result?: SourceConnectionTestResponse
  error?: string
}

/**
 * The one „Verbindung testen" of the Pfad- und URL-Formulare (#1940) - the same button and the same
 * two result shapes in the Anlage-Assistent and on the Reiter „Quelle".
 *
 * <p>#514: a result belongs to the probe it was measured with - the source type, the library it was
 * aimed at and every entered field. Rather than clearing it from every field's onChange, the
 * outcome carries that probe and is simply not rendered once the current one differs: a stale
 * „erreichbar" survives neither a changed address nor a changed source type, including one changed
 * while the request was still in flight.
 */
export default function SourceConnectionTest({
  sourceType,
  values,
  libraryId,
  size = 'medium',
}: SourceConnectionTestProps) {
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [testing, setTesting] = useState(false)
  const token = JSON.stringify({ sourceType, libraryId, values })
  const visible = outcome?.token === token ? outcome : null

  async function handleTest() {
    const validationError = validateLibrarySourceFields(sourceType, values)
    if (validationError) {
      setOutcome({ token, error: validationError })
      return
    }
    setOutcome(null)
    setTesting(true)
    try {
      const result = await testLibrarySource({
        sourceType,
        ...deriveLibrarySourceConfigPayload(sourceType, values),
        ...(libraryId ? { libraryId } : {}),
      })
      setOutcome({ token, result })
    } catch (err) {
      setOutcome({
        token,
        error: err instanceof Error ? err.message : 'Verbindung konnte nicht getestet werden',
      })
    } finally {
      setTesting(false)
    }
  }

  return (
    <Box>
      <Button onClick={() => void handleTest()} disabled={testing} variant="outlined" size={size}>
        {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
      </Button>
      {visible?.error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {visible.error}
        </Alert>
      )}
      {visible?.result && (
        <Alert severity={visible.result.reachable ? 'success' : 'warning'} sx={{ mt: 1 }}>
          {visible.result.message}
        </Alert>
      )}
    </Box>
  )
}
