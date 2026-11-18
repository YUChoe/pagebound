<script setup>
// 문서 원문 보기 /source/{path} (spec/features/F9-code-blocks.md 5절 P1): 읽기 전용 CodeMirror, 줄 번호, 검색(Ctrl+F)
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { EditorView, keymap, lineNumbers } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { markdown } from '@codemirror/lang-markdown'
import { languages } from '@codemirror/language-data'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { highlightSelectionMatches, search, searchKeymap } from '@codemirror/search'
import { api } from '../api.js'
import { routePath } from '../router.js'
import { encodePath } from '../markdown/wikiPaths.js'

const route = useRoute()
const host = ref(null)
const page = ref(null)
const status = ref('loading')
let view = null

async function load() {
  status.value = 'loading'
  try {
    page.value = await api.page(routePath(route))
    document.title = `원문: ${page.value.title} - Pagebound`
    status.value = 'ok'
    await new Promise((r) => setTimeout(r))
    view?.destroy()
    view = new EditorView({
      parent: host.value,
      state: EditorState.create({
        doc: page.value.content,
        extensions: [
          lineNumbers(),
          EditorState.readOnly.of(true),
          markdown({ codeLanguages: languages }),
          syntaxHighlighting(defaultHighlightStyle),
          search({ top: true }),
          highlightSelectionMatches(),
          keymap.of(searchKeymap),
          EditorView.lineWrapping,
        ],
      }),
    })
  } catch {
    status.value = 'error'
  }
}

watch(() => routePath(route), load, { immediate: true })
onBeforeUnmount(() => view?.destroy())
</script>

<template>
  <article class="doc-card">
    <p v-if="status === 'loading'" class="muted">불러오는 중…</p>
    <p v-else-if="status === 'error'">문서를 불러오지 못했습니다.</p>
    <template v-if="page">
      <div class="history-head">
        <h1 class="doc-title">원문: {{ page.title }}</h1>
        <div class="doc-actions">
          <a class="btn" :href="'/w/' + encodePath(page.path) + '.md'" target="_blank" rel="noopener">.md</a>
          <router-link class="btn" :to="'/w/' + encodePath(page.path)">문서로</router-link>
        </div>
      </div>
      <p class="muted">읽기 전용 · Ctrl+F 검색 · v{{ page.version }}</p>
    </template>
    <div ref="host" class="source-view" />
  </article>
</template>
