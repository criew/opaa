import { useCallback, useRef, useState } from 'react'
import type { LlmModelDiscoveryRequest, LlmModelDiscoveryResponse } from '@/shared/api/types'
import { discoverLlmModels } from '../../../services/modelApi'

export type DiscoveryState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'done'; response: LlmModelDiscoveryResponse }
  | { status: 'rejected'; message: string }

/**
 * The model list of one endpoint at a time. Only the answer to the latest request counts: a
 * slower answer to an earlier address never overwrites the list of the current one.
 */
export function useModelDiscovery() {
  const [state, setState] = useState<DiscoveryState>({ status: 'idle' })
  const latest = useRef(0)

  const discover = useCallback(async (request: LlmModelDiscoveryRequest) => {
    const ticket = ++latest.current
    setState({ status: 'loading' })
    try {
      const response = await discoverLlmModels(request)
      if (ticket === latest.current) setState({ status: 'done', response })
    } catch (err) {
      if (ticket === latest.current) {
        setState({
          status: 'rejected',
          message:
            err instanceof Error ? err.message : 'Die Modelle konnten nicht abgerufen werden.',
        })
      }
    }
  }, [])

  const reset = useCallback(() => {
    latest.current++
    setState({ status: 'idle' })
  }, [])

  const models = state.status === 'done' ? state.response.models : []
  return { state, models, discover, reset }
}
