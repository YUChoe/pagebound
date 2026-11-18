// 붙여넣기 P1 (spec/features/F1-editor.md 2절): 엑셀 TSV → 표, HTML → 마크다운(turndown), 선택 텍스트 + URL → 링크
import TurndownService from 'turndown'
import { gfm } from 'turndown-plugin-gfm'

export function isUrl(text) {
  return /^https?:\/\/\S+$/.test(text.trim())
}

// 모든 비어 있지 않은 줄의 탭 수가 같고 1 이상이면 표 (엑셀·구글 시트 셀 복사)
export function isTsv(text) {
  const lines = text.replace(/\r?\n$/, '').split(/\r?\n/)
  if (lines.length < 1) return false
  const counts = lines.map((l) => (l.match(/\t/g) ?? []).length)
  return counts[0] >= 1 && counts.every((c) => c === counts[0]) && (lines.length > 1 || counts[0] >= 2)
}

const cell = (s) => s.trim().replace(/\|/g, '\\|').replace(/\s*\n\s*/g, ' ')

export function tsvToMarkdown(text) {
  const rows = text.replace(/\r?\n$/, '').split(/\r?\n/).map((l) => l.split('\t').map(cell))
  const header = rows[0]
  const lines = [`| ${header.join(' | ')} |`, `| ${header.map(() => '---').join(' | ')} |`]
  rows.slice(1).forEach((r) => lines.push(`| ${r.join(' | ')} |`))
  return lines.join('\n') + '\n'
}

// 코드 에디터(VS Code 등)가 주는 span/div 뿐인 HTML은 일반 텍스트로 처리한다
const STRUCTURE = /<(p|h[1-6]|ul|ol|li|table|img|a\s|strong|b|em|i|blockquote|pre|code)\b/i

export function isRichHtml(html) {
  return !!html && STRUCTURE.test(html)
}

let service = null

function turndown() {
  if (!service) {
    service = new TurndownService({ headingStyle: 'atx', codeBlockStyle: 'fenced', bulletListMarker: '-', emDelimiter: '*' })
    service.use(gfm)
    service.remove(['script', 'style', 'meta', 'title'])
  }
  return service
}

// { markdown, images: [src] } — 이미지 src(data:, http(s))는 호출 측이 첨부로 바꾼다
export function htmlToMarkdown(html) {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const images = [...doc.querySelectorAll('img[src]')].map((img) => img.getAttribute('src'))
    .filter((src) => /^(data:image\/|https?:\/\/)/.test(src))
  const markdown = turndown().turndown(doc.body).trim()
  return { markdown: markdown + '\n', images: [...new Set(images)] }
}

export function dataUrlToFile(dataUrl, name) {
  const [head, body] = dataUrl.split(',', 2)
  const mime = /data:([^;]+)/.exec(head)?.[1] ?? 'image/png'
  const bytes = head.includes(';base64') ? Uint8Array.from(atob(body), (c) => c.charCodeAt(0)) : new TextEncoder().encode(decodeURIComponent(body))
  return new File([bytes], name, { type: mime })
}

export function imageName(src, index, now = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}`
  if (src.startsWith('data:')) {
    const ext = (/data:image\/([a-z0-9+]+)/i.exec(src)?.[1] ?? 'png').replace('jpeg', 'jpg').replace('svg+xml', 'svg')
    return `paste-${stamp}-${index + 1}.${ext}`
  }
  const last = decodeURIComponent(new URL(src).pathname.split('/').pop() || '')
  return /\.[a-z0-9]{2,5}$/i.test(last) ? last : `paste-${stamp}-${index + 1}.png`
}
