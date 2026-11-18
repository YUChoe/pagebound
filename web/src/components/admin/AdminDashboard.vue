<script setup>
// 대시보드 (F12 4절)
import { onMounted, ref } from 'vue'
import { adminApi, formatBytes } from '../../admin.js'
import { SOURCE_LABELS } from '../../blame.js'
import { formatDate } from '../../time.js'

const d = ref(null)
onMounted(async () => {
  document.title = '관리 - Pagebound'
  d.value = await adminApi.dashboard()
})
</script>

<template>
  <div v-if="d">
    <h1 class="doc-title">대시보드</h1>
    <div v-if="d.users.activeAdmins <= 1" class="banner warn">활성 관리자가 1명뿐입니다. 2명 이상 지정을 권장합니다.</div>
    <div class="stat-grid">
      <div class="stat"><b>{{ d.pages.active }}</b><span>문서</span><small>휴지통 {{ d.pages.trash }} · 초안 {{ d.pages.drafts }}</small></div>
      <div class="stat"><b>{{ d.attachments.count }}</b><span>첨부</span><small>{{ formatBytes(d.attachments.bytes) }}</small></div>
      <div class="stat"><b>{{ formatBytes(d.dbBytes) }}</b><span>DB</span><small>여유 {{ formatBytes(d.freeBytes) }} · 백업 {{ d.lastBackup ?? '없음' }}</small></div>
      <div class="stat"><b>{{ Object.values(d.users.byRole).reduce((a, b) => a + b, 0) }}</b><span>활성 사용자</span>
        <small>{{ Object.entries(d.users.byRole).map(([r, n]) => `${r} ${n}`).join(' · ') }} · 비활성 {{ d.users.inactive }}</small></div>
    </div>
    <h2>최근 7일 편집</h2>
    <p><span v-for="(n, s) in d.editsLast7Days" :key="s" class="badge" :class="'src-' + s">{{ SOURCE_LABELS[s] ?? s }} {{ n }}</span>
      <span v-if="!Object.keys(d.editsLast7Days).length" class="muted">없음</span></p>
    <h2>정리 대상</h2>
    <ul>
      <li><router-link to="/admin/cleanup?kind=unreferenced-attachments">미참조 첨부 {{ d.cleanup.unreferencedAttachments }}</router-link></li>
      <li><router-link to="/admin/cleanup?kind=red-links">레드 링크 {{ d.cleanup.redLinks }}</router-link></li>
      <li><router-link to="/admin/trash">7일 내 영구 삭제 예정 {{ d.cleanup.purgeWithin7Days }}</router-link></li>
    </ul>
    <h2>최근 감사 로그</h2>
    <ul class="audit-mini">
      <li v-for="a in d.recentAudit" :key="a.id"><span class="muted">{{ formatDate(a.createdAt) }}</span> {{ a.actor ?? '시스템' }} · {{ a.action }} {{ a.targetType }} {{ a.targetId }}</li>
    </ul>
    <p class="muted">서버 {{ d.version }} · 기동 {{ formatDate(d.startedAt) }}</p>
  </div>
</template>
