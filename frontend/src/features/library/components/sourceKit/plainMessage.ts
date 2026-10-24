const CODE =
  /\s*\((?:HTTP\s*\d{3}[^):]*|AADSTS\d+|invalid_\w+|STATUS_[A-Z_]+|[A-Z][a-z]+(?:[A-Z][a-z]+)+)(?::\s*([^)]*))?\)/g

/**
 * A connector message without protocol codes (HTTP status, SMB status, provider error ids): what
 * a person reads is the sentence, not the code. A code's own explanation after a colon stays.
 */
export function plainMessage(message: string): string {
  return message
    .replace(CODE, (_match, explanation?: string) => (explanation ? `: ${explanation}` : ''))
    .replace(/\s*\bHTTP \d{3}\b/g, '')
    .replace(/\s+([.,:;])/g, '$1')
    .replace(/:\s*:/g, ':')
    .trim()
}
