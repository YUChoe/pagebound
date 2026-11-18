import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { blockPlugin, calloutPlugin, headingPlugin, taskListPlugin, wikiLinkPlugin } from './plugins.js'

// Wiki.js markdownCore 설정과 동일: allowHTML, linkify, linebreaks
export function createMarkdown() {
  const md = new MarkdownIt({ html: true, linkify: true, breaks: true })
  md.use(wikiLinkPlugin).use(headingPlugin).use(calloutPlugin).use(taskListPlugin).use(blockPlugin)
  // 링크 미리보기용: 자동 링크 종류 표시 (linkify = 문장 속 URL, autolink = <URL>) — spec/features/F10-link-preview.md 2절
  const linkOpen = md.renderer.rules.link_open ?? ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    const markup = tokens[idx].markup
    if (markup === 'linkify' || markup === 'autolink') tokens[idx].attrSet('data-link', markup)
    return linkOpen(tokens, idx, options, env, self)
  }
  return md
}

const shared = createMarkdown()

// { html, sections } — sections: [{ index, number, level, depth, title, id }]
export function renderMarkdown(content, { path = '', links = {} } = {}) {
  const env = { path, links, sections: [] }
  const raw = shared.render(content ?? '', env)
  const html = DOMPurify.sanitize(raw, { ADD_ATTR: ['loading'] })
  return { html, sections: env.sections }
}
