/** The `apiKey` part of a model step's save: omitted keeps the stored key, empty removes it. */
export function apiKeyPart(apiKey: string, remove: boolean): { apiKey?: string } {
  if (apiKey !== '') return { apiKey }
  return remove ? { apiKey: '' } : {}
}
