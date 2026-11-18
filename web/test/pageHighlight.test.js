import { describe, expect, it } from 'vitest'
import { highlightInPage } from '../src/enhance/pageHighlight.js'

describe('문서 내 강조 ?hl= (F11 P1)', () => {
  it('일치에 mark, 접힌 섹션·코드블록 펼침', () => {
    const el = document.createElement('div')
    el.innerHTML = '<section class="sec folded"><h2><span class="sec-fold">▸</span>설정</h2>'
      + '<div class="codeblock is-collapsed"><span class="cb-toggle">▸</span><pre><code>max_connections=200</code></pre></div>'
      + '<p>Max_Connections 설명</p></section>'
    document.body.append(el)
    expect(highlightInPage(el, ['max_connections'])).toBe(2)
    expect([...el.querySelectorAll('mark.hl')].map((m) => m.textContent)).toEqual(['max_connections', 'Max_Connections'])
    expect(el.querySelector('.sec').classList.contains('folded')).toBe(false)
    expect(el.querySelector('.codeblock').classList.contains('is-collapsed')).toBe(false)
    el.remove()
  })
})
