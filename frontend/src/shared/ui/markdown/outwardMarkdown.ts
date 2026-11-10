import type { Definition, Nodes, Parents } from 'mdast'
import { fromMarkdown } from 'mdast-util-from-markdown'
import { gfmFromMarkdown } from 'mdast-util-gfm'
import { gfm } from 'micromark-extension-gfm'
import { blockedImageText, isAllowedLinkTarget } from './linkPolicy'

/** Where an answer leaving the application may point to: the link targets the chat allows. */
export interface OutwardPolicy {
  allowedOrigins?: readonly string[]
  pageHref?: string
}

/**
 * An answer's Markdown as it may leave the application - every copy and export format starts
 * from it. It applies the chat's rules: an image becomes text with its address, a link stays a
 * link only for the own origin and the allowed link targets (otherwise text with its address),
 * raw HTML is text. Addresses shown as text sit in code spans, so a Markdown tool that gets the
 * text neither loads nor links them. Markdown without links, images or HTML comes back unchanged.
 */
export function answerForOutside(markdown: string, policy: OutwardPolicy = {}): string {
  if (!CAN_LINK_OR_LOAD.test(markdown)) return markdown
  // Text spliced back in can form new syntax with its neighbours (a link text that becomes a
  // definition), so the rewrite runs until nothing changes. What does not settle loses every
  // link, image and HTML outside code; code blocks and code spans stay as they are.
  let current = markdown
  for (let pass = 0; pass < MAX_PASSES; pass++) {
    const next = rewriteOnce(current, policy)
    if (next === current) return current
    current = next
  }
  return asTextOutsideCode(current)
}

const MAX_PASSES = 4

/**
 * What every link, image, reference, autolink and piece of HTML needs: a closing bracket, an
 * angle bracket, or the start of a bare address. Text without any of them is returned unparsed.
 */
const CAN_LINK_OR_LOAD = /[\]<]|:\/\/|www\.|@/i

function parse(markdown: string) {
  return fromMarkdown(markdown, { extensions: [gfm()], mdastExtensions: [gfmFromMarkdown()] })
}

function rewriteOnce(markdown: string, policy: OutwardPolicy): string {
  const tree = parse(markdown)
  const definitions = new Map<string, Definition>()
  collectDefinitions(tree, definitions)
  const context: Context = {
    source: markdown,
    definitions,
    allowedOrigins: policy.allowedOrigins ?? [],
    pageHref: policy.pageHref ?? window.location.href,
  }
  return render(tree, context)
}

interface Context {
  source: string
  definitions: Map<string, Definition>
  allowedOrigins: readonly string[]
  pageHref: string
}

function collectDefinitions(node: Nodes, into: Map<string, Definition>) {
  // The first definition of a label wins, as in CommonMark.
  if (node.type === 'definition' && !into.has(node.identifier)) into.set(node.identifier, node)
  if ('children' in node) node.children.forEach((child) => collectDefinitions(child, into))
}

/** The text that replaces `node`'s source range. */
function render(node: Nodes, context: Context): string {
  switch (node.type) {
    case 'image':
      return imageText(node.alt, node.url)
    case 'imageReference':
      return imageText(node.alt, context.definitions.get(node.identifier)?.url ?? '')
    case 'link':
      return linkText(node, node.url, context)
    case 'linkReference':
      return linkText(node, context.definitions.get(node.identifier)?.url ?? '', context)
    case 'definition':
      // References are written out inline above, so a definition has nothing left to resolve.
      return ''
    case 'html':
      return escapeText(node.value)
    default:
      return 'children' in node ? spliceChildren(node, context) : slice(node, context)
  }
}

function imageText(alt: string | null | undefined, url: string): string {
  const text = blockedImageText(escapeText(alt?.replace(/\s+/g, ' ') ?? ''))
  return url === '' ? text : `${text} (${codeSpan(url)})`
}

function linkText(node: Parents, url: string, context: Context): string {
  const children = childrenText(node, context)
  if (isAllowedLinkTarget(url, context.pageHref, context.allowedOrigins)) {
    // A bare allowed address stays bare: a reader links it to the same target.
    if (node.type === 'link' && ownAddress(node, url) !== null) return children
    // The pasted text has no chat page to resolve a relative or scheme-only address against.
    const href = new URL(url, context.pageHref).href
    return `[${children}](${/[()]/.test(href) ? `<${href}>` : href})`
  }
  if (url === '') return children
  const shown = ownAddress(node, url)
  if (shown !== null) return codeSpan(shown)
  return children === '' ? codeSpan(url) : `${children} (${codeSpan(url)})`
}

/**
 * The text of a link that shows its own address - an autolink, or a bare address, e-mail or
 * www. address a reader links by itself (whose target adds `mailto:` or `http://`) - else null.
 * Such a link is shown once, as its text; repeating the address would leave a bare one behind.
 */
function ownAddress(node: Parents, url: string): string | null {
  if (node.children.length !== 1 || node.children[0].type !== 'text') return null
  const text = node.children[0].value
  return [text, `mailto:${text}`, `http://${text}`].includes(url) ? text : null
}

/** A link or image rewritten as text, whose neighbours' brackets could now form a link. */
function isRewritten(node: Nodes, context: Context): boolean {
  switch (node.type) {
    case 'image':
    case 'imageReference':
      return true
    case 'link':
      // A link showing its own address keeps its text as is, so it cannot unbalance brackets.
      return (
        !isAllowedLinkTarget(node.url, context.pageHref, context.allowedOrigins) &&
        ownAddress(node, node.url) === null
      )
    case 'linkReference':
      return !isAllowedLinkTarget(
        context.definitions.get(node.identifier)?.url ?? '',
        context.pageHref,
        context.allowedOrigins,
      )
    default:
      return false
  }
}

/** The source between the first and the last child, each child replaced by its rendering. */
function childrenText(node: Parents, context: Context): string {
  const first = node.children[0]?.position?.start.offset
  const last = node.children[node.children.length - 1]?.position?.end.offset
  if (first === undefined || last === undefined) return ''
  return spliceRange(node, first, last, context)
}

function spliceChildren(node: Parents, context: Context): string {
  const start = node.position?.start.offset ?? 0
  const end = node.position?.end.offset ?? context.source.length
  return spliceRange(node, start, end, context)
}

function spliceRange(node: Parents, start: number, end: number, context: Context): string {
  // Next to a rewritten link, a literal bracket of the text around it could close a link the
  // answer never had ("[[b](x)](y)"), so the text's own brackets are escaped there.
  const guard = node.children.some((child) => isRewritten(child, context))
  let out = ''
  let at = start
  for (const child of node.children) {
    const childStart = child.position?.start.offset
    const childEnd = child.position?.end.offset
    if (childStart === undefined || childEnd === undefined) continue
    const rendered =
      guard && child.type === 'text'
        ? escapeBrackets(slice(child, context))
        : render(child, context)
    out += context.source.slice(at, childStart) + rendered
    at = childEnd
  }
  return out + context.source.slice(at, end)
}

/** `text`, a Markdown source, with its unescaped square brackets escaped. */
function escapeBrackets(text: string): string {
  let out = ''
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (char === '\\' && i + 1 < text.length) {
      out += char + text[i + 1]
      i++
    } else {
      out += char === '[' || char === ']' ? `\\${char}` : char
    }
  }
  return out
}

/**
 * The last resort: everything outside code escaped as text, so nothing links, loads or is HTML;
 * code blocks and code spans are kept byte for byte.
 */
function asTextOutsideCode(markdown: string): string {
  const escapeOutsideCode = (node: Nodes): string => {
    if (node.type === 'code' || node.type === 'inlineCode') return markdown.slice(...range(node))
    if (!('children' in node) || isLinkLike(node.type)) {
      return escapeText(markdown.slice(...range(node)))
    }
    const [start, end] = range(node)
    let out = ''
    let at = start
    for (const child of node.children) {
      const [childStart, childEnd] = range(child)
      out += markdown.slice(at, childStart) + escapeOutsideCode(child)
      at = childEnd
    }
    return out + markdown.slice(at, end)
  }
  const range = (node: Nodes): [number, number] => [
    node.position?.start.offset ?? 0,
    node.position?.end.offset ?? 0,
  ]
  const tree = parse(markdown)
  return (
    markdown.slice(0, range(tree)[0]) + escapeOutsideCode(tree) + markdown.slice(range(tree)[1])
  )
}

function isLinkLike(type: string): boolean {
  return ['link', 'linkReference', 'image', 'imageReference', 'definition'].includes(type)
}

function slice(node: Nodes, context: Context): string {
  return context.source.slice(node.position?.start.offset ?? 0, node.position?.end.offset ?? 0)
}

/**
 * Backslash-escapes what could start an image, a link, an autolink, HTML or emphasis, so text
 * taken from an image description or from raw HTML stays text in any Markdown reader.
 */
function escapeText(text: string): string {
  return text.replace(/[\\`*_[\]()<>!&|~:]/g, '\\$&')
}

/** `text` as a code span: shown as written, never a link or an image. */
function codeSpan(text: string): string {
  const flat = text.replace(/\s+/g, ' ')
  const longestRun = Math.max(0, ...(flat.match(/`+/g) ?? []).map((run) => run.length))
  const fence = '`'.repeat(longestRun + 1)
  const pad = flat.startsWith('`') || flat.endsWith('`') ? ' ' : ''
  return `${fence}${pad}${flat}${pad}${fence}`
}
