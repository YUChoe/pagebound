// markdown-it 플러그인: 위키 링크, 헤딩 번호, 콜아웃, 태스크리스트, 코드블록, 표, 이미지
import { encodePath, lastSegment, resolve } from './wikiPaths.js'
import { parseInfo } from './codeInfo.js'

// 서버 MarkdownAnalyzer.WIKI_LINK 와 같은 패턴
const WIKI_LINK = /\[\[([^[\]|#\n]*)(#[^[\]|\n]*)?(?:\|([^[\]\n]*))?\]\]/y

export function headingId(title) {
  return 'h-' + title.trim().replace(/\s+/g, '-')
}

export function wikiLinkPlugin(md) {
  md.inline.ruler.before('link', 'wikilink', (state, silent) => {
    if (state.src.charCodeAt(state.pos) !== 0x5b || state.src.charCodeAt(state.pos + 1) !== 0x5b) return false
    WIKI_LINK.lastIndex = state.pos
    const m = WIKI_LINK.exec(state.src)
    if (!m) return false
    const env = state.env ?? {}
    const target = resolve(env.path ?? '', m[1])
    if (!target) return false
    if (!silent) {
      const info = env.links?.[target]
      const fragment = m[2] ? m[2].substring(1).trim() : ''
      const href = '/w/' + encodePath(info?.path ?? target) + (fragment ? '#' + encodeURIComponent(headingId(fragment)) : '')
      const open = state.push('link_open', 'a', 1)
      open.attrSet('href', href)
      open.attrSet('class', info?.exists ? 'wikilink' : 'wikilink red')
      open.attrSet('data-path', target)
      state.push('text', '', 0).content = m[3]?.trim() || lastSegment(target)
      state.push('link_close', 'a', -1)
    }
    state.pos = WIKI_LINK.lastIndex
    return true
  })
}

// 서버 MarkdownAnalyzer.sections 와 같은 번호 규칙 (중첩 깊이 기준)
export function headingPlugin(md) {
  md.core.ruler.push('heading_numbers', (state) => {
    const open = []
    const counters = [0, 0, 0, 0, 0, 0, 0]
    const used = new Map()
    const sections = []
    let index = 0
    state.tokens.forEach((token, i) => {
      if (token.type !== 'heading_open') return
      const level = Number(token.tag.substring(1))
      while (open.length && open[open.length - 1] >= level) open.pop()
      const depth = open.length
      counters[depth]++
      for (let d = depth + 1; d < counters.length; d++) counters[d] = 0
      open.push(level)
      const number = counters.slice(0, depth + 1).join('.') + '.'
      const title = state.tokens[i + 1].content.trim()
      let id = headingId(title)
      const n = (used.get(id) ?? 0) + 1
      used.set(id, n)
      if (n > 1) id += '-' + n
      index++
      token.attrSet('id', id)
      token.attrSet('data-section', String(index))
      token.attrSet('data-number', number)
      token.attrSet('data-depth', String(depth))
      sections.push({ index, number, level, depth, title, id })
    })
    state.env.sections = sections
  })
  md.renderer.rules.heading_open = (tokens, idx, options, env, self) =>
    self.renderToken(tokens, idx, options) +
    `<span class="h-num">${tokens[idx].attrGet('data-number')}</span> `
}

const CALLOUT = /^\[!(INFO|NOTE|TIP|SUCCESS|WARNING|DANGER)\]\s*/i

export function calloutPlugin(md) {
  md.core.ruler.push('callout', (state) => {
    const t = state.tokens
    for (let i = 0; i + 2 < t.length; i++) {
      if (t[i].type !== 'blockquote_open' || t[i + 1].type !== 'paragraph_open' || t[i + 2].type !== 'inline') continue
      const inline = t[i + 2]
      const m = CALLOUT.exec(inline.content)
      if (!m) continue
      const type = m[1].toLowerCase()
      t[i].attrSet('class', `callout callout-${type}`)
      t[i].attrSet('data-callout', m[1].toUpperCase())
      const first = inline.children[0]
      if (first?.type === 'text') {
        first.content = first.content.replace(CALLOUT, '')
        const next = inline.children[1]
        if (!first.content && next && (next.type === 'softbreak' || next.type === 'hardbreak')) {
          inline.children.splice(0, 2)
        }
      }
    }
  })
}

const TASK = /^\[([ xX])\]\s+/

export function taskListPlugin(md) {
  md.core.ruler.push('task_list', (state) => {
    const t = state.tokens
    for (let i = 2; i < t.length; i++) {
      if (t[i].type !== 'inline' || t[i - 1].type !== 'paragraph_open' || t[i - 2].type !== 'list_item_open') continue
      const first = t[i].children[0]
      const m = first?.type === 'text' ? TASK.exec(first.content) : null
      if (!m) continue
      first.content = first.content.substring(m[0].length)
      const box = new state.Token('html_inline', '', 0)
      box.content = `<input type="checkbox" disabled${m[1] === ' ' ? '' : ' checked'}> `
      t[i].children.unshift(box)
      t[i - 2].attrSet('class', 'task-item')
    }
  })
}

export function blockPlugin(md) {
  const escape = md.utils.escapeHtml
  md.renderer.rules.fence = (tokens, idx) => {
    const token = tokens[idx]
    const info = parseInfo(token.info)
    const code = token.content.replace(/\n$/, '')
    if (info.lang === 'mermaid') {
      return `<div class="mermaid-block"><pre class="mermaid-src">${escape(code)}</pre></div>\n`
    }
    const lines = code.length ? code.split('\n').length : 1
    const attrs = [
      `data-lang="${escape(info.lang)}"`,
      `data-title="${escape(info.title)}"`,
      `data-collapsed="${info.collapsed ? 1 : 0}"`,
      `data-linenums="${info.lineNumbers ? 1 : 0}"`,
      `data-start="${info.start}"`,
      `data-lines="${lines}"`,
    ].join(' ')
    const cls = info.lang ? ` class="language-${escape(info.lang)}"` : ''
    return `<div class="codeblock" ${attrs}><pre><code${cls}>${escape(code)}</code></pre></div>\n`
  }
  md.renderer.rules.table_open = () => '<div class="table-wrap"><table>\n'
  md.renderer.rules.table_close = () => '</table></div>\n'
  const image = md.renderer.rules.image
  md.renderer.rules.image = (tokens, idx, options, env, self) => {
    tokens[idx].attrSet('loading', 'lazy')
    return image(tokens, idx, options, env, self)
  }
}
