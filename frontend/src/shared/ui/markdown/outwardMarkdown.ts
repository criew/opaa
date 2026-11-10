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
  // Text spliced back in can form new syntax with its neighbours (a link text that becomes a
  // definition), so the rewrite runs until nothing changes; what does not settle is all text.
  let current = markdown
  for (let pass = 0; pass < MAX_PASSES; pass++) {
    const next = rewriteOnce(current, policy)
    if (next === current) return current
    current = next
  }
  return escapeText(current)
}

const MAX_PASSES = 4

function rewriteOnce(markdown: string, policy: OutwardPolicy): string {
  const tree = fromMarkdown(markdown, {
    extensions: [gfm()],
    mdastExtensions: [gfmFromMarkdown()],
  })
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
    // The pasted text has no chat page to resolve a relative or scheme-only address against.
    const href = new URL(url, context.pageHref).href
    return `[${children}](${/[()]/.test(href) ? `<${href}>` : href})`
  }
  const plain = node.children.length === 1 && node.children[0].type === 'text'
  if (url === '' || (plain && 'value' in node.children[0] && node.children[0].value === url)) {
    return url === '' ? children : codeSpan(url)
  }
  return children === '' ? codeSpan(url) : `${children} (${codeSpan(url)})`
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
  let out = ''
  let at = start
  for (const child of node.children) {
    const childStart = child.position?.start.offset
    const childEnd = child.position?.end.offset
    if (childStart === undefined || childEnd === undefined) continue
    out += context.source.slice(at, childStart) + render(child, context)
    at = childEnd
  }
  return out + context.source.slice(at, end)
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
