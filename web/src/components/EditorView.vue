<script setup>
// 문서 편집/작성 화면 (spec/features/F1-editor.md). /edit/{path}[?section=n], 없는 경로면 새 문서
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import MarkdownEditor from './editor/MarkdownEditor.vue'
import TagInput from './editor/TagInput.vue'
import AttachmentPanel from './editor/AttachmentPanel.vue'
import ConflictDialog from './editor/ConflictDialog.vue'
import { api, ApiError } from '../api.js'
import { routePath } from '../router.js'
import { encodePath, lastSegment } from '../markdown/wikiPaths.js'
import { renderMarkdown } from '../markdown/render.js'
import { enhanceCodeBlocks } from '../enhance/codeBlocks.js'
import { enhanceLinkPreviews } from '../enhance/linkPreview.js'
import { load, remove, save as store } from '../enhance/storage.js'
import { MAX_BYTES, MAX_FILES, fileMarkdown, isImage, pasteName, placeholder } from '../editor/paste.js'
import { dataUrlToFile, imageName } from '../editor/clipboard.js'
import { canEdit, refreshSession, session } from '../session.js'
import { formatDate } from '../time.js'
import { bumpTree } from '../tree/store.js'

const route = useRoute()
const router = useRouter()
const editor = ref(null)
const preview = ref(null)
const path = computed(() => routePath(route))
const section = computed(() => (route.query.section ? Number(route.query.section) : null))
const status = ref('loading') // loading | ready | forbidden | error
const mode = ref('edit') // edit | create
const pageId = ref(null)
const draft = ref(false)
const version = ref(1)
const title = ref('')
const content = ref('')
const tags = ref([])
const summary = ref('')
const links = ref({})
const pagePaths = ref([])
const attachments = ref([])
const showPreview = ref(true)
const saving = ref(false)
const message = ref('')
const warnings = ref([])
const conflict = ref(null)
const restore = ref(null)
const loaded = ref('') // 마지막으로 불러오거나 저장한 본문
const draftKey = computed(() => `pb.draft:${path.value}:${section.value ?? 'all'}`)
const dirty = computed(() => status.value === 'ready' && content.value !== loaded.value)

async function loadEditor() {
  status.value = 'loading'
  message.value = ''
  warnings.value = []
  if (!session.loaded) await refreshSession()
  if (!canEdit()) {
    status.value = 'forbidden'
    return
  }
  try {
    const page = await api.page(path.value)
    const src = await api.source(page.id, section.value)
    mode.value = 'edit'
    pageId.value = page.id
    draft.value = false
    version.value = src.version
    title.value = src.title
    content.value = src.content
    tags.value = src.tags
    links.value = page.links
    attachments.value = await api.attachments(page.id)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404 && e.body?.redirect) {
      router.replace('/edit/' + encodePath(e.body.redirect))
      return
    }
    if (!(e instanceof ApiError && e.status === 404) || section.value) {
      status.value = 'error'
      return
    }
    mode.value = 'create'
    pageId.value = null
    version.value = 1
    title.value = lastSegment(path.value)
    content.value = ''
    tags.value = []
    links.value = {}
    attachments.value = []
  }
  loaded.value = content.value
  summary.value = ''
  const stored = load(draftKey.value)
  restore.value = stored && stored.content !== content.value ? stored : null
  status.value = 'ready'
  loadPaths()
}

async function loadPaths() {
  try {
    const out = []
    const walk = (nodes) => nodes.forEach((n) => { out.push({ path: n.path, title: n.title }); walk(n.children) })
    walk(await api.tree())
    pagePaths.value = out
  } catch {
    pagePaths.value = []
  }
}

function applyRestore() {
  title.value = restore.value.title
  content.value = restore.value.content
  tags.value = restore.value.tags ?? tags.value
  restore.value = null
}

function discardRestore() {
  remove(draftKey.value)
  restore.value = null
}

// 5초 디바운스 로컬 임시 저장
let autosaveTimer = null
watch([title, content, tags], () => {
  clearTimeout(autosaveTimer)
  autosaveTimer = setTimeout(() => {
    if (dirty.value) {
      store(draftKey.value, { title: title.value, content: content.value, tags: tags.value,
        baseVersion: version.value, savedAt: new Date().toISOString() })
    }
  }, 5000)
}, { deep: true })

// 미리보기 300ms 디바운스
let previewTimer = null
let codeControl = null
watch([content, showPreview, status], () => {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(renderPreview, 300)
})
async function renderPreview() {
  await nextTick()
  const el = preview.value
  if (!el || !showPreview.value) return
  codeControl?.disconnect()
  el.innerHTML = renderMarkdown(content.value, { path: path.value, links: links.value }).html
  codeControl = enhanceCodeBlocks(el, 'preview')
  enhanceLinkPreviews(el, { authenticated: session.authenticated })
}
function syncScroll(ratio) {
  const el = preview.value
  if (el) el.scrollTop = ratio * (el.scrollHeight - el.clientHeight)
}

// 첫 업로드 시 미저장 새 문서는 초안을 만들어 page id를 확보한다
async function ensurePageId() {
  if (pageId.value) return pageId.value
  const { id } = await api.createDraft()
  pageId.value = id
  draft.value = true
  return id
}

async function handleFiles(files) {
  const list = files.slice(0, MAX_FILES)
  if (files.length > MAX_FILES) message.value = `한 번에 ${MAX_FILES}개까지 업로드합니다`
  for (const file of list) {
    if (file.size > MAX_BYTES) {
      message.value = `${file.name}: 50MB를 넘어 업로드하지 않았습니다`
      continue
    }
    const name = pasteName(file)
    const image = isImage(file)
    const marker = placeholder(name, image)
    editor.value.insert(marker + (list.length > 1 ? '\n' : ''))
    try {
      const id = await ensurePageId()
      const info = await api.upload(id, file, name)
      editor.value.replaceFirst(marker, fileMarkdown(info, image))
      attachments.value = await api.attachments(id)
    } catch (e) {
      editor.value.replaceFirst(marker, '')
      message.value = `${name} 업로드 실패${e.body?.message ? ': ' + e.body.message : ''}`
    }
  }
}

// HTML 붙여넣기의 이미지(data:, 외부 URL)를 현재 문서 첨부로 저장하고 본문 URL을 바꾼다 (F1 2절 P1)
async function importImages(sources) {
  let failed = 0
  for (const [i, src] of sources.slice(0, MAX_FILES).entries()) {
    try {
      const id = await ensurePageId()
      const name = imageName(src, i)
      const info = src.startsWith('data:')
        ? await api.upload(id, dataUrlToFile(src, name), name)
        : await api.fetchAttachment(id, src, name)
      let replaced = true
      while (replaced) replaced = editor.value.replaceFirst(`](${src})`, `](${info.url})`)
    } catch {
      failed++
    }
  }
  if (pageId.value) attachments.value = await api.attachments(pageId.value)
  if (failed) message.value = `이미지 ${failed}개는 가져오지 못해 원래 주소로 남겼습니다`
}

function insertAttachment(a) {
  editor.value.insert(fileMarkdown(a, a.mime.startsWith('image/')))
}

async function renameAttachment(a, name) {
  try {
    const res = await api.renameAttachment(pageId.value, a.id, name)
    content.value = content.value.replaceAll(a.url, res.attachment.url)
    if (!draft.value) version.value = res.pageVersion
    attachments.value = await api.attachments(pageId.value)
  } catch (e) {
    message.value = e.body?.code === 'NAME_EXISTS' ? '같은 이름의 첨부가 있습니다' : '이름 변경 실패'
  }
}

async function removeAttachment(a) {
  await api.deleteAttachment(pageId.value, a.id)
  attachments.value = await api.attachments(pageId.value)
}

async function copyForeign(warning) {
  const [sourceId, ...rest] = warning.substring('FOREIGN_ATTACHMENT:'.length).split('/')
  const name = rest.join('/')
  const info = await api.copyAttachment(pageId.value, Number(sourceId), name)
  content.value = content.value.replaceAll(`/p/${sourceId}/files/${encodeURIComponent(name).replace(/\+/g, '%20')}`, info.url)
  warnings.value = warnings.value.filter((w) => w !== warning)
  attachments.value = await api.attachments(pageId.value)
}

async function save() {
  if (saving.value) return
  saving.value = true
  message.value = ''
  try {
    const common = { title: title.value, content: content.value, tags: tags.value, summary: summary.value }
    let saved
    if (mode.value === 'create' && !draft.value) {
      saved = await api.createPage({ ...common, path: path.value, createParents: true })
    } else if (mode.value === 'create') {
      saved = await api.updatePage(pageId.value, { ...common, version: 1, path: path.value, createParents: true })
    } else {
      saved = await api.updatePage(pageId.value, { ...common, version: version.value, section: section.value })
    }
    remove(draftKey.value)
    loaded.value = content.value
    version.value = saved.version
    bumpTree()
    if (saved.warnings.length) {
      mode.value = 'edit'
      pageId.value = saved.id
      draft.value = false
      warnings.value = saved.warnings
      message.value = '저장했습니다. 아래 경고를 확인하세요.'
      return
    }
    router.push('/w/' + encodePath(saved.path))
  } catch (e) {
    if (e instanceof ApiError && e.body?.code === 'VERSION_CONFLICT') {
      conflict.value = { server: e.body.currentContent, version: e.body.currentVersion }
    } else if (e instanceof ApiError && e.body?.code === 'PATH_EXISTS') {
      message.value = '같은 경로의 문서가 이미 있습니다'
    } else {
      message.value = `저장 실패${e.body?.message ? ': ' + e.body.message : ''}`
    }
  } finally {
    saving.value = false
  }
}

function overwrite() {
  version.value = conflict.value.version
  conflict.value = null
  save()
}

function reloadServer() {
  store(draftKey.value, { title: title.value, content: content.value, tags: tags.value,
    baseVersion: version.value, savedAt: new Date().toISOString() })
  conflict.value = null
  loaded.value = content.value
  loadEditor()
}

function cancel() {
  router.push(mode.value === 'create' ? '/w/home' : '/w/' + encodePath(path.value))
}

const tools = [
  ['제목', () => editor.value.insertBlock('## 제목')],
  ['굵게', () => editor.value.wrap('**', '**', '굵게')],
  ['코드', () => editor.value.wrap('`', '`', 'code')],
  ['코드블록', () => editor.value.insertBlock('```\n코드\n```')],
  ['링크', () => editor.value.wrap('[', '](https://)', '링크')],
  ['위키 링크', () => editor.value.wrap('[[', ']]', '문서 경로')],
  ['표', () => editor.value.insertBlock('| 항목 | 내용 |\n|------|------|\n|  |  |')],
  ['체크리스트', () => editor.value.insertBlock('- [ ] 할 일')],
  ['콜아웃', () => editor.value.insertBlock('> [!INFO]\n> 내용')],
]

function beforeUnload(e) {
  if (dirty.value) e.preventDefault()
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  codeControl?.disconnect()
})
onBeforeRouteLeave(() => !dirty.value || window.confirm('저장하지 않은 변경이 있습니다. 나갈까요? (임시 저장본은 남습니다)'))

watch(() => [path.value, section.value], loadEditor, { immediate: true })
</script>

<template>
  <div v-if="status === 'loading'" class="muted pad">불러오는 중…</div>
  <div v-else-if="status === 'forbidden'" class="doc-card">
    <p>편집 권한이 없습니다.
      <router-link v-if="!session.authenticated" :to="{ name: 'login', query: { next: route.fullPath } }">로그인</router-link></p>
  </div>
  <div v-else-if="status === 'error'" class="doc-card"><p>문서를 불러오지 못했습니다.</p></div>
  <div v-else class="editor-page">
    <div class="editor-bar">
      <input v-model="title" class="title-input" placeholder="제목" />
      <span class="muted">
        {{ mode === 'create' ? '새 문서' : 'v' + version }} · /w/{{ path }}<template v-if="section"> · 섹션 {{ section }}</template>
      </span>
      <span class="spacer" />
      <button type="button" @click="showPreview = !showPreview">{{ showPreview ? '미리보기 숨기기' : '미리보기' }}</button>
      <button type="button" @click="cancel">취소</button>
      <button type="button" class="primary" :disabled="saving" @click="save">저장 (Ctrl+S)</button>
    </div>
    <TagInput v-model="tags" />
    <input v-model="summary" class="summary-input" placeholder="편집 요약 (선택)" />
    <div v-if="restore" class="banner">
      임시 저장본({{ formatDate(restore.savedAt) }})이 있습니다.
      <button type="button" @click="applyRestore">복원</button>
      <button type="button" @click="discardRestore">버리기</button>
    </div>
    <div v-if="message" class="banner">{{ message }}</div>
    <div v-if="warnings.length" class="banner warn">
      <div v-for="w in warnings" :key="w">
        <template v-if="w.startsWith('FOREIGN_ATTACHMENT:')">
          다른 문서의 첨부를 참조합니다: {{ w.substring(19) }}
          <button type="button" @click="copyForeign(w)">이 페이지로 복사</button>
        </template>
        <template v-else>존재하지 않는 첨부를 참조합니다: {{ w.substring(19) }}</template>
      </div>
      <router-link :to="'/w/' + encodePath(path)">문서 보기</router-link>
    </div>
    <div class="toolbar">
      <button v-for="[label, run] in tools" :key="label" type="button" @click="run">{{ label }}</button>
    </div>
    <div class="editor-body" :class="{ 'no-preview': !showPreview }">
      <MarkdownEditor ref="editor" v-model="content" :page-paths="pagePaths" @save="save" @files="handleFiles"
                      @scroll="syncScroll" @images="importImages" />
      <div v-show="showPreview" ref="preview" class="markdown-body preview-pane" />
      <AttachmentPanel :items="attachments" :content="content" :has-page="!!pageId" @upload="handleFiles"
                       @insert="insertAttachment" @rename="renameAttachment" @remove="removeAttachment" />
    </div>
  </div>
  <ConflictDialog v-if="conflict" :server="conflict.server" :mine="content" :server-version="conflict.version"
                  @overwrite="overwrite" @reload="reloadServer" @close="conflict = null" />
</template>
