<script setup>
// 관리자 화면 /admin/** (spec/features/F12-admin.md 3절). ADMIN만 (서버 API가 최종 확인)
import { session } from '../../session.js'

const MENU = [
  ['/admin', '대시보드'], ['/admin/users', '사용자'], ['/admin/trash', '휴지통'], ['/admin/tags', '태그'],
  ['/admin/tokens', '토큰·에이전트'], ['/admin/audit', '감사 로그'], ['/admin/cleanup', '정리 도구'],
  ['/admin/settings', '설정'], ['/admin/maintenance', '유지보수'],
]
</script>

<template>
  <article class="doc-card admin">
    <p v-if="session.loaded && session.role !== 'ADMIN'">관리자만 볼 수 있습니다.</p>
    <template v-else>
      <nav class="admin-nav">
        <router-link v-for="[to, label] in MENU" :key="to" :to="to" :class="{ exact: to === '/admin' }">{{ label }}</router-link>
      </nav>
      <router-view />
    </template>
  </article>
</template>
