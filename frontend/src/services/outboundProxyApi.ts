import type { OutboundProxyRequest, OutboundProxyResponse } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

const OUTBOUND_PROXY = '/v1/admin/outbound-proxy'

/** The house-wide outbound proxy and its exclusion list (system administration only). */
export async function getOutboundProxy(): Promise<OutboundProxyResponse> {
  try {
    const { data } = await client.get<OutboundProxyResponse>(OUTBOUND_PROXY)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Replaces both; `proxyUrl: null` lets every connection go direct. */
export async function updateOutboundProxy(
  request: OutboundProxyRequest,
): Promise<OutboundProxyResponse> {
  try {
    const { data } = await client.put<OutboundProxyResponse>(OUTBOUND_PROXY, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}
