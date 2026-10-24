const CODE =
  /\s*\((?:HTTP\s*\d{3}[^):]*|AADSTS\d+|invalid_\w+|STATUS_[A-Z_]+|[A-Z][a-z]+(?:[A-Z][a-z]+)+)(?::\s*([^)]*))?\)/g

/**
 * A connector message without protocol codes (HTTP status, SMB status, provider error ids): what
 * a person reads is the sentence, not the code. A code's own explanation after a colon stays.
 */
export function plainMessage(message: string): string {
  // the HTTP client's own wording when no German message came back
  if (/Request failed with status code|Network Error|timeout of \d+ms/i.test(message)) {
    return 'Die Quelle hat nicht geantwortet.'
  }
  return (
    message
      .replace(CODE, (_match, explanation?: string) => (explanation ? `: ${explanation}` : ''))
      .replace(/\s*\bHTTP \d{3}\b/g, '')
      .replace(/\s+([.,:;])/g, '$1')
      .replace(/:\s*:/g, ':')
      // a sentence that only repeats a link next to the message („Erneut laden“, „Von Hand eintragen“)
      .replace(/\s*(Bitte (später )?erneut (versuchen|laden)|Geben Sie [^.]* von Hand an)\.?/g, '')
      .replace(/^[\s:;,.]+/, '')
      .trim()
  )
}
