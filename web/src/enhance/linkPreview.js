// 웹 링크 미리보기 (spec/features/F10-link-preview.md 2절)
// - URL 단독 문단: 카드 / 문장·목록 속 URL, <URL>: 인라인 칩 / [텍스트](URL): 아이콘 + 상태 배지
// - 모든 대상 링크 hover 300ms 후 팝오버. 실패·미지원은 일반 링크 그대로
// - 비로그인: 서버 호출 없이 URL에서 뽑은 project!13 형태 라벨만 (제목·상태 없음)
// 프로바이더 응답 문자열은 textContent로만 넣는다 (HTML 미해석)
import { api } from '../api.js'
import { renderMarkdown } from '../markdown/render.js'
import { enhanceCodeBlocks } from './codeBlocks.js'
import { relativeTime } from '../time.js'

const STATE_LABEL = { open: 'Open', draft: 'Draft', merged: 'Merged', closed: 'Closed' }
const KIND_ICON = { merge_request: '⑂', issue: '◎', commit: '◆', project: '▣', wiki: '▤', blob: '▢', web: '↗' }
const BATCH = 100
const HOVER_DELAY = 300
const cache = new Map() // url → Promise<preview> (페이지 이동·에디터 미리보기 재사용)

// 비로그인 칩 라벨: /{ns..}/{proj}[/-]/merge_requests|issues|commit/{id}
const GITLAB_PATH = /^\/((?:[^/]+\/)+?)([^/]+)\/(?:-\/)?(merge_requests|issues|commit)\/([0-9a-fA-F]{7,40}|\d+)/

export function parseLabel(url) {
  let u
  try { u = new URL(url) } catch { return null }
  const m = GITLAB_PATH.exec(u.pathname)
  if (!m) return null
  const [, , proj, action, id] = m
  if (action === 'merge_requests' && /^\d+$/.test(id)) return { kind: 'merge_request', label: `${proj}!${id}` }
  if (action === 'issues' && /^\d+$/.test(id)) return { kind: 'issue', label: `${proj}#${id}` }
  if (action === 'commit') return { kind: 'commit', label: `${proj}@${id.slice(0, 8)}` }
  return null
}

// 줄 단위 단독 여부: 앞뒤가 문단 경계 또는 <br> (공백 텍스트 무시)
function lineEdge(a, dir) {
  let n = a[dir]
  while (n && n.nodeType === 3 && !n.textContent.trim()) n = n[dir]
  return !n || n.nodeName === 'BR'
}

// 대상 링크와 표시 형태: card | chip | decorate
export function collectLinks(container) {
  const out = []
  container.querySelectorAll('a[href^="http://"], a[href^="https://"]').forEach((a) => {
    if (a.closest('pre, code, .codeblock, .lp-card, h1, h2, h3, h4, h5, h6')) return
    const url = a.getAttribute('href')
    // linkify의 스킴 없는 도메인 추정(build.sh 등)은 제외: 본문에 http(s)://로 쓴 URL만
    if (a.dataset.link === 'linkify' && !/^https?:\/\//i.test(a.textContent)) return
    const auto = a.dataset.link === 'linkify' || a.dataset.link === 'autolink'
    let mode = auto ? 'chip' : 'decorate'
    if (a.dataset.link === 'linkify' && a.parentElement?.tagName === 'P' && !a.closest('li, td, th, blockquote')
      && lineEdge(a, 'previousSibling') && lineEdge(a, 'nextSibling')) {
      mode = 'card'
    }
    out.push({ a, url, mode })
  })
  return out
}

function el(tag, className, text) {
  const e = document.createElement(tag)
  if (className) e.className = className
  if (text != null) e.textContent = text
  return e
}

function stateBadge(state) {
  return state ? el('span', `lp-state lp-${state}`, STATE_LABEL[state] ?? state) : null
}

function icon(kind) {
  return el('span', 'lp-icon', KIND_ICON[kind] ?? '↗')
}

// 칩: [아이콘] label 제목 [상태]
function renderChip(a, p) {
  const d = p.data ?? {}
  a.textContent = ''
  a.classList.add('lp-chip')
  a.append(icon(p.kind), el('span', 'lp-label', p.kind === 'web' ? (d.title ?? d.label) : d.label))
  if (p.kind !== 'web' && d.title) a.append(el('span', 'lp-title', d.title))
  const badge = stateBadge(d.state)
  if (badge) a.append(badge)
}

// 작성자 텍스트 유지 + 앞 아이콘 + 뒤 상태 배지
function renderDecorate(a, p) {
  a.classList.add('lp-decorated')
  a.prepend(icon(p.kind))
  const badge = stateBadge(p.data?.state)
  if (badge) a.append(badge)
}

const CODE_LANG = { h: 'c', hpp: 'cpp', yml: 'yaml', kt: 'kotlin', sh: 'bash', md: 'markdown' }

function snippetBlock(d) {
  const ext = (d.title ?? '').split('.').pop().toLowerCase()
  const fence = '`'.repeat(Math.max(3, ...(d.snippet.match(/`+/g) ?? []).map((s) => s.length + 1)))
  const md = `${fence}${CODE_LANG[ext] ?? ext} start=${d.startLine}\n${d.snippet}\n${fence}\n`
  const box = el('div', 'lp-snippet')
  box.innerHTML = renderMarkdown(md).html
  enhanceCodeBlocks(box, 'link-preview')
  return box
}

// 카드 본문 (카드·팝오버 공용)
export function cardContent(url, p, onRefresh) {
  const d = p.data ?? {}
  const card = el('div', 'lp-card')
  const head = el('div', 'lp-head')
  if (p.kind === 'web' && d.favicon) {
    const fav = el('img', 'lp-favicon')
    fav.src = d.favicon
    fav.referrerPolicy = 'no-referrer'
    fav.alt = ''
    fav.onerror = () => fav.remove()
    head.append(fav)
  } else {
    head.append(icon(p.kind))
  }
  const link = el('a', 'lp-link')
  link.href = url
  if (p.kind !== 'web') link.append(el('span', 'lp-label', d.label))
  link.append(el('span', 'lp-title', d.title ?? url))
  head.append(link)
  const badge = stateBadge(d.state)
  if (badge) head.append(badge)
  if (onRefresh) {
    const btn = el('button', 'lp-refresh', '⟳')
    btn.type = 'button'
    btn.title = '미리보기 새로 고침'
    btn.onclick = (e) => { e.preventDefault(); onRefresh() }
    head.append(btn)
  }
  card.append(head)

  const meta = []
  if (d.note) meta.push('댓글 링크')
  if (d.author) meta.push(d.author + (d.reviewers?.length ? ` → ${d.reviewers.join(', ')}` : ''))
  if (d.assignees?.length) meta.push('담당: ' + d.assignees.join(', '))
  if (d.sourceBranch) meta.push(`${d.sourceBranch} → ${d.targetBranch}`)
  if (d.pipeline) meta.push('파이프라인: ' + d.pipeline)
  if (d.mergeStatus) meta.push('머지: ' + d.mergeStatus)
  if (d.milestone) meta.push('마일스톤: ' + d.milestone)
  if (d.dueDate) meta.push('마감: ' + d.dueDate)
  if (d.ref) meta.push('ref: ' + d.ref)
  if (d.defaultBranch) meta.push('기본 브랜치: ' + d.defaultBranch)
  if (d.comments) meta.push(`댓글 ${d.comments}`)
  const when = d.updatedAt ?? d.createdAt
  if (when) meta.push(relativeTime(when))
  if (p.kind === 'web' && d.siteName) meta.push(d.siteName)
  if (meta.length) card.append(el('div', 'lp-meta', meta.join(' · ')))
  if (d.labels?.length) {
    const labels = el('div', 'lp-labels')
    d.labels.forEach((l) => labels.append(el('span', 'chip', l)))
    card.append(labels)
  }
  if (d.description) card.append(el('div', 'lp-desc', d.description))
  if (d.image) {
    const img = el('img', 'lp-image')
    img.src = d.image
    img.referrerPolicy = 'no-referrer'
    img.loading = 'lazy'
    img.alt = ''
    img.onerror = () => img.remove()
    card.append(img)
  }
  if (d.snippet) card.append(snippetBlock(d))
  return card
}

function fetchPreviews(urls) {
  const missing = urls.filter((u) => !cache.has(u))
  for (let i = 0; i < missing.length; i += BATCH) {
    const chunk = missing.slice(i, i + BATCH)
    const request = api.linkPreviews(chunk).catch(() => [])
    chunk.forEach((u) => cache.set(u, request.then((list) => list.find((r) => r.url === u) ?? null)))
  }
  return urls.map((u) => cache.get(u))
}

let popover = null
let hoverTimer = null

function hidePopover() {
  clearTimeout(hoverTimer)
  popover?.remove()
  popover = null
}

function attachHover(a, url, getPreview) {
  a.addEventListener('mouseenter', () => {
    clearTimeout(hoverTimer)
    hoverTimer = setTimeout(() => {
      const p = getPreview()
      if (!p) return
      hidePopover()
      popover = el('div', 'lp-popover')
      popover.append(cardContent(url, p))
      const r = a.getBoundingClientRect()
      popover.style.left = `${Math.max(8, r.left + window.scrollX)}px`
      popover.style.top = `${r.bottom + window.scrollY + 6}px`
      popover.addEventListener('mouseleave', hidePopover)
      document.body.append(popover)
    }, HOVER_DELAY)
  })
  a.addEventListener('mouseleave', (e) => {
    clearTimeout(hoverTimer)
    if (popover && !popover.contains(e.relatedTarget)) hidePopover()
  })
}

function apply(item, p) {
  const { a, url, mode } = item
  a.classList.remove('lp-loading')
  if (!p || p.status !== 'ok') return
  let current = p
  if (mode === 'card') {
    // 링크 자리에 카드 블록. 뒤따르는 <br>은 카드가 블록이라 불필요
    const holder = el('div', 'lp-card-holder')
    let next = a.nextSibling
    while (next && next.nodeType === 3 && !next.textContent.trim()) next = next.nextSibling
    if (next?.nodeName === 'BR') next.remove()
    a.replaceWith(holder)
    const draw = () => {
      holder.replaceChildren(cardContent(url, current, async () => {
        cache.delete(url)
        current = await api.refreshLinkPreview(url)
        if (current?.status === 'ok') draw()
      }))
    }
    draw()
    return
  }
  if (mode === 'chip') renderChip(a, p)
  else renderDecorate(a, p)
  attachHover(a, url, () => current)
}

// 반환: 완료 Promise (테스트·측정용)
export async function enhanceLinkPreviews(container, { authenticated }) {
  hidePopover()
  const items = collectLinks(container)
  if (!items.length) return
  if (!authenticated) {
    items.forEach(({ a, mode }) => {
      const parsed = parseLabel(a.getAttribute('href'))
      if (!parsed || mode === 'decorate') return
      renderChip(a, { kind: parsed.kind, data: { label: parsed.label } })
    })
    return
  }
  items.forEach(({ a, mode }) => { if (mode !== 'decorate') a.classList.add('lp-loading') })
  const promises = fetchPreviews([...new Set(items.map((i) => i.url))])
  const byUrl = new Map([...new Set(items.map((i) => i.url))].map((u, i) => [u, promises[i]]))
  await Promise.all(items.map(async (item) => apply(item, await byUrl.get(item.url))))
}

export function clearLinkPreviewCache() {
  cache.clear()
}
