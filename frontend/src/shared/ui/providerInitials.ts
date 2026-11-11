/**
 * The one or two letters a provider is recognised by: "Verzeichnisdienst" → V, "Landes Portal" →
 * LP. Punctuation never stands for a word: "Stadtverwaltung (Keycloak)" → SK.
 */
export function providerInitials(name: string): string {
  const initials = name
    .trim()
    .split(/\s+/)
    .map((word) => word.match(/[\p{L}\p{N}]/u)?.[0] ?? '')
    .filter(Boolean)
  if (initials.length === 0) return '?'
  return initials.slice(0, 2).join('').toUpperCase()
}
