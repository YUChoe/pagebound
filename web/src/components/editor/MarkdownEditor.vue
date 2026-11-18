<script setup>
// CodeMirror 6 마크다운 에디터 (spec/features/F1-editor.md 1절)
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorView, drawSelection, highlightActiveLine, keymap, lineNumbers } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { autocompletion, completionKeymap } from '@codemirror/autocomplete'
import { htmlToMarkdown, isRichHtml, isTsv, isUrl, tsvToMarkdown } from '../../editor/clipboard.js'

const props = defineProps({
  modelValue: { type: String, default: '' },
  pagePaths: { type: Array, default: () => [] }, // [{ path, title }] — [[ 자동완성
})
const emit = defineEmits(['update:modelValue', 'save', 'files', 'scroll', 'images'])
const host = ref(null)
let view = null

// [[ 입력 시 문서 경로 자동완성
function wikiLinkSource(context) {
  const match = context.matchBefore(/\[\[[^\]|#\n]*$/)
  if (!match) return null
  return {
    from: match.from + 2,
    options: props.pagePaths.map((p) => ({ label: p.path, detail: p.title, apply: `${p.path}]]` })),
    validFor: /^[^\]|#\n]*$/,
  }
}

function wrap(before, after = before, fallback = '') {
  const { from, to } = view.state.selection.main
  const text = view.state.sliceDoc(from, to) || fallback
  view.dispatch({
    changes: { from, to, insert: before + text + after },
    selection: { anchor: from + before.length, head: from + before.length + text.length },
  })
  view.focus()
}

function insert(text) {
  const { from, to } = view.state.selection.main
  view.dispatch({ changes: { from, to, insert: text }, selection: { anchor: from + text.length } })
  view.focus()
}

// 현재 줄 시작에 블록 삽입 (빈 줄이 아니면 다음 줄로)
function insertBlock(text) {
  const line = view.state.doc.lineAt(view.state.selection.main.head)
  const prefix = line.text.trim() ? '\n\n' : ''
  const at = line.text.trim() ? line.to : line.from
  view.dispatch({ changes: { from: at, insert: prefix + text + '\n' }, selection: { anchor: at + prefix.length + text.length } })
  view.focus()
}

// 붙여넣기 P1: 선택 + URL → 링크, 스프레드시트 셀 → 표, 서식 있는 HTML → 마크다운 (이미지는 images 이벤트로 첨부화)
function pasteText(event, v) {
  const text = event.clipboardData?.getData('text/plain') ?? ''
  const html = event.clipboardData?.getData('text/html') ?? ''
  const { from, to } = v.state.selection.main
  let handled = true
  if (from !== to && isUrl(text)) {
    wrap('[', `](${text.trim()})`)
  } else if (isTsv(text) && /<table/i.test(html)) {
    insert(tsvToMarkdown(text))
  } else if (isRichHtml(html)) {
    const converted = htmlToMarkdown(html)
    insert(converted.markdown)
    if (converted.images.length) emit('images', converted.images)
  } else {
    handled = false
  }
  if (handled) event.preventDefault()
  return handled
}

// 첫 번째 search를 replacement로 (업로드 플레이스홀더 치환). 찾으면 true
function replaceFirst(search, replacement) {
  const index = view.state.doc.toString().indexOf(search)
  if (index < 0) return false
  view.dispatch({ changes: { from: index, to: index + search.length, insert: replacement } })
  return true
}

function scrollRatio() {
  const s = view.scrollDOM
  return s.scrollHeight > s.clientHeight ? s.scrollTop / (s.scrollHeight - s.clientHeight) : 0
}

function filesFrom(list) {
  return list ? [...list].filter((f) => f.kind === undefined || f.kind === 'file') : []
}

onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        lineNumbers(),
        history(),
        drawSelection(),
        highlightActiveLine(),
        EditorView.lineWrapping,
        markdown(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        autocompletion({ override: [wikiLinkSource] }),
        keymap.of([
          { key: 'Mod-s', preventDefault: true, run: () => { emit('save'); return true } },
          { key: 'Mod-k', preventDefault: true, run: () => { wrap('[', '](https://)', '링크'); return true } },
          ...completionKeymap,
          ...defaultKeymap,
          ...historyKeymap,
          indentWithTab,
        ]),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) emit('update:modelValue', u.state.doc.toString())
        }),
        EditorView.domEventHandlers({
          paste(event, v) {
            const files = filesFrom(event.clipboardData?.files)
            if (files.length) {
              event.preventDefault()
              emit('files', files)
              return true
            }
            return pasteText(event, v)
          },
          drop(event, v) {
            const files = filesFrom(event.dataTransfer?.files)
            if (!files.length) return false
            event.preventDefault()
            const pos = v.posAtCoords({ x: event.clientX, y: event.clientY })
            if (pos != null) v.dispatch({ selection: { anchor: pos } })
            emit('files', files)
            return true
          },
          scroll() {
            emit('scroll', scrollRatio())
          },
        }),
      ],
    }),
  })
})

// 외부에서 값이 바뀌면(복원, 첨부 이름 변경) 문서 교체
watch(() => props.modelValue, (value) => {
  if (view && value !== view.state.doc.toString()) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } })
  }
})

onBeforeUnmount(() => view?.destroy())

defineExpose({ wrap, insert, insertBlock, replaceFirst, focus: () => view?.focus() })
</script>

<template>
  <div ref="host" class="md-editor" />
</template>
