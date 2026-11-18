// 헤딩 기준 섹션 접기 (spec/features/F5-page-view.md 2절)
// 본문 최상위 자식들을 <section class="sec"> 로 감싸고 헤딩 앞에 접기 버튼을 붙인다.

const HEADING = /^H[1-6]$/

export function enhanceSections(container, { editBase = null } = {}) {
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
