const ESCAPING_POSITIONS = new Set(['fixed', 'sticky'])

interface RuleList {
  readonly length: number
  [index: number]: CSSRule
  deleteRule?: (index: number) => void
}

/**
 * Keeps what docx-preview rendered below `root` inside its box. A Word file reaches the rendered
 * CSS twice: VML shapes carry their `style` attribute verbatim, and style colours are pasted into
 * a stylesheet as text, where a crafted value closes the rule and opens its own. So every
 * stylesheet below `root` keeps only style rules that do not reach the shadow host, and neither
 * those rules nor inline styles may position anything fixed or sticky.
 */
export function confineRenderedDocx(root: ParentNode) {
  for (const style of Array.from(root.querySelectorAll('style'))) {
    const sheet = style.sheet
    if (sheet) confineRules(sheet.cssRules, (index) => sheet.deleteRule(index), true)
  }
  for (const element of Array.from(root.querySelectorAll<HTMLElement | SVGElement>('[style]'))) {
    if (ESCAPING_POSITIONS.has(element.style.position)) element.style.removeProperty('position')
  }
}

function confineRules(rules: RuleList, deleteRule: (index: number) => void, topLevel: boolean) {
  for (let index = rules.length - 1; index >= 0; index--) {
    const rule = rules[index]
    if (!isStyleRule(rule) || (topLevel && /:host|:root/i.test(rule.selectorText))) {
      deleteRule(index)
      continue
    }
    if (ESCAPING_POSITIONS.has(rule.style.position)) rule.style.removeProperty('position')
    const nested = (rule as CSSStyleRule & { cssRules?: CSSRuleList }).cssRules
    if (nested && nested.length > 0) {
      const owner = rule as CSSStyleRule & { deleteRule: (index: number) => void }
      confineRules(nested, (at) => owner.deleteRule(at), false)
    }
  }
}

function isStyleRule(rule: CSSRule): rule is CSSStyleRule {
  return 'selectorText' in rule && 'style' in rule
}
