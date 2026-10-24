import type { SourceConnectionTestRequest } from '@/shared/api/types'
import type { SourceIssue } from '../../utils/sourceIssue'
import { connectionFields, payloadUnder } from './sourceConnection'
import type { SourceConfiguration, SourceFormContext } from './types'

/** Every issue of the values, from `issues` where the connector has it, else its first one. */
export function allSourceIssues<V>(
  configuration: SourceConfiguration<V>,
  values: V,
  context: SourceFormContext,
): SourceIssue[] {
  if (configuration.issues) return configuration.issues(values, context)
  const issue = configuration.validate(values, context)
  return issue ? [issue] : []
}

/**
 * The connection test of the values as the shared frame runs it: the request fields a create
 * would send, scoped and framed like every probe. Null while the values cannot be sent.
 */
export function sourceTestRequest<V>(
  configuration: SourceConfiguration<V>,
  values: V,
  context: SourceFormContext,
): SourceConnectionTestRequest | null {
  const fields = connectionFields(context)
  const payload = payloadUnder(configuration.toPayload(values), fields)
  return fields.probeRequest({
    sourceType: context.sourceType,
    ...payload,
  }) as SourceConnectionTestRequest
}
