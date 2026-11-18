import { describe, expect, it } from 'vitest'
import { parseLineAnchor, parseRanges, splitLines } from '../src/enhance/codeLines.js'
import { renderMarkdown } from '../src/markdown/render.js'
import { enhanceCodeBlocks } from '../src/enhance/codeBlocks.js'

describe('코드블록 P1 (F9)', () => {
  it('여러 줄 span을 줄마다 닫고 다시 연다', () => {
    expect(splitLines('<span class="c">/* a\nb */</span> x\ny')).toEqual(['<span class="c">/* a</span>', '<span class="c">b */</span> x', 'y'])
  })

  it('줄 강조 범위와 줄 앵커 파싱', () => {
    expect([...parseRanges('3,5-7')]).toEqual([3, 5, 6, 7])
    expect(parseLineAnchor('#code-2-L120')).toEqual({ block: 2, line: 120 })
    expect(parseLineAnchor('#h-x')).toBeNull()
  })

  it('펜스 순번·강조 속성, 접힘은 until-found, 줄 앵커 진입 시 펼치고 강조', () => {
    const md = '```mermaid\ngraph TD\n```\n\n```java {2} start=10 collapsed\na\nb\nc\n```\n'
    const el = document.createElement('div')
    el.innerHTML = renderMarkdown(md).html
    const block = el.querySelector('.codeblock')
    expect(block.dataset.index).toBe('1')
    const control = enhanceCodeBlocks(el, 'test-page')
    expect(block.querySelector('pre').getAttribute('hidden')).toBe('until-found')
    expect(control.revealLine('#code-1-L11')).toBe(true)
    expect(block.querySelector('pre').hasAttribute('hidden')).toBe(false)
    expect(block.querySelector('.cb-line.cb-hl').dataset.line).toBe('11')
    expect(block.querySelector('.cb-line.cb-target').textContent).toBe('b')
  })
})
