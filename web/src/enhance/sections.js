// 헤딩 기준 섹션 접기 (spec/features/F5-page-view.md 2절)와 섹션 영구 링크 복사 (2.1절)
// 본문 최상위 자식들을 <section class="sec"> 로 감싸고 헤딩 앞에 접기 버튼을 붙인다.
import { copyText } from './storage.js'

const HEADING = /^H[1-6]$/

function flash(button, text) {
  const original = button.textContent
  button.textContent = text
  setTimeout(() => { button.textContent = original }, 1500)
}

// permalinkBase: `{origin}/p/{id}` — 있으면 헤딩마다 `#` 복사 버튼
export function enhanceSections(container, { editBase = null, permalinkBase = null } = {}) {
  const nodes = [...container.childNodes]
  const root = document.createDocumentFragment()
  const stack = []

  for (const node of nodes) {
    const isHeading = node.nodeType === 1 && HEADING.test(node.tagName) && node.dataset.section
    if (!isHeading) {
      ;(stack.length ? stack[stack.length - 1].body : root).appendChild(node)
      continue
    }
    const level = Number(node.tagName.substring(1))
    while (stack.length && stack[stack.length - 1].level >= level) stack.pop()

    const section = document.createElement('section')
    section.className = 'sec'
    const body = document.createElement('div')
    body.className = 'sec-body'

    const fold = document.createElement('button')
    fold.type = 'button'
    fold.className = 'sec-fold'
    fold.textContent = '▾'
    fold.title = '섹션 접기/펼치기'
    fold.addEventListener('click', () => {
      const folded = section.classList.toggle('folded')
      fold.textContent = folded ? '▸' : '▾'
    })
    node.prepend(fold)
    if (permalinkBase && node.id) {
      const anchor = document.createElement('button')
      anchor.type = 'button'
      anchor.className = 'sec-anchor'
      anchor.textContent = '#'
      anchor.title = '이 섹션의 영구 링크 복사'
      anchor.dataset.url = `${permalinkBase}#${encodeURIComponent(node.id)}`
      anchor.addEventListener('click', async () => {
        await copyText(anchor.dataset.url)
        flash(anchor, '복사됨')
      })
      node.append(anchor)
    }
    if (editBase) {
      const edit = document.createElement('a')
      edit.className = 'sec-edit'
      edit.href = `${editBase}?section=${node.dataset.section}`
      edit.textContent = '[편집]'
      node.append(edit)
    }

    section.append(node, body)
    ;(stack.length ? stack[stack.length - 1].body : root).appendChild(section)
    stack.push({ level, body })
  }
  container.appendChild(root)
}

// 앵커 대상을 감싼 접힌 섹션을 펼친 뒤 스크롤 (spec/features/F5-page-view.md 2.1절 규칙 2)
export function revealAnchor(id) {
  const target = id ? document.getElementById(id) : null
  if (!target) return false
  for (let sec = target.closest('.sec.folded'); sec; sec = sec.parentElement?.closest('.sec.folded')) {
    sec.classList.remove('folded')
    const fold = sec.querySelector(':scope > :is(h1, h2, h3, h4, h5, h6) > .sec-fold')
    if (fold) fold.textContent = '▾'
  }
  target.scrollIntoView()
  return true
}
