import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { blockPlugin, calloutPlugin, headingPlugin, taskListPlugin, wikiLinkPlugin } from './plugins.js'

// Wiki.js markdownCore 설정과 동일: allowHTML, linkify, linebreaks
export function createMarkdown() {
  const md = new MarkdownIt({ html: true, linkify: true, breaks: true })
  md.use(wikiLinkPlugin).use(headingPlugin).use(calloutPlugin).use(taskListPlugin).use(blockPlugin)
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
