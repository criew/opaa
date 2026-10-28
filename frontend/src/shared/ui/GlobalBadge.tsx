import MetaBadge from './MetaBadge'

/**
 * Marks a surface as application-wide (mockups 2b/2c). A label like every other (guidelines 5.5):
 * the scope is carried by the visible word, never by colour (accessibility.md).
 */
export default function GlobalBadge() {
  return <MetaBadge>Global</MetaBadge>
}
