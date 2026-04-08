<template>
  <div class="settings-layout">
    <div class="settings-nav">
      <h2 class="nav-title">{{ t('settings.title') }}</h2>
      <router-link
        v-for="section in sections"
        :key="section.id"
        :to="section.path"
        class="nav-item"
        :class="{ active: isActive(section.path) }"
      >
        <span class="nav-icon" v-html="section.icon"></span>
        {{ section.label }}
      </router-link>
    </div>

    <div class="settings-content">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const { t } = useI18n()

const sections = computed(() => [
  {
    id: 'model',
    path: '/settings/models',
    label: t('settings.sections.model'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 8V4H8"/><rect x="4" y="8" width="16" height="12" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/></svg>',
  },
  {
    id: 'system',
    path: '/settings/system',
    label: t('settings.sections.system'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.09a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9c0 .66.26 1.3.73 1.77.47.47 1.11.73 1.77.73H21a2 2 0 1 1 0 4h-.09c-.66 0-1.3.26-1.77.73-.47.47-.73 1.11-.73 1.77z"/></svg>',
  },
  {
    id: 'image',
    path: '/settings/image',
    label: t('settings.sections.image'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>',
  },
  {
    id: 'tts',
    path: '/settings/tts',
    label: t('settings.sections.tts'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>',
  },
  {
    id: 'stt',
    path: '/settings/stt',
    label: t('settings.sections.stt'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>',
  },
  {
    id: 'music',
    path: '/settings/music',
    label: t('settings.sections.music'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
  },
  {
    id: 'video',
    path: '/settings/video',
    label: t('settings.sections.video'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>',
  },
  {
    id: 'about',
    path: '/settings/about',
    label: t('settings.sections.about'),
    icon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>',
  },
])

function isActive(path: string) {
  return route.path === path
}
</script>

<style scoped>
.settings-layout { display: flex; height: 100%; overflow: hidden; background: var(--mc-bg); }
.settings-nav { width: 220px; min-width: 220px; background: var(--mc-bg-elevated); border-right: 1px solid var(--mc-border); padding: 20px 12px; }
.nav-title { font-size: 13px; font-weight: 600; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.05em; padding: 0 8px; margin: 0 0 12px; }
.nav-item { display: flex; align-items: center; gap: 10px; width: 100%; padding: 8px 12px; border: none; background: none; border-radius: 6px; font-size: 14px; font-weight: 400; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; text-align: left; text-decoration: none; }
.nav-item:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.nav-item.active { background: var(--mc-primary-bg); color: var(--mc-primary); font-weight: 500; }
.nav-item + .nav-item { margin-top: 2px; }
.nav-icon { width: 18px; height: 18px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; }
.nav-icon :deep(svg) { width: 18px; height: 18px; display: block; }
.settings-content { flex: 1; overflow-y: auto; overflow-x: hidden; padding: 24px; }

@media (max-width: 900px) {
  .settings-layout { flex-direction: column; }
  .settings-nav { width: 100%; min-width: 100%; border-right: none; border-bottom: 1px solid var(--mc-border); }
}
</style>
