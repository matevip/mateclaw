<template>
  <div class="app-layout">
    <!-- 移动端背景遮罩 -->
    <Transition name="fade">
      <div v-if="isMobile && mobileMenuOpen" class="sidebar-backdrop" @click="mobileMenuOpen = false"></div>
    </Transition>

    <!-- 左侧导航栏 -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed && !isMobile, 'mobile-open': mobileMenuOpen }">
      <!-- Logo -->
      <div class="sidebar-logo">
        <div class="logo-icon">
          <img src="/logo/mateclaw_logo_s.png" alt="MateClaw" class="logo-img" />
        </div>
        <transition name="fade">
          <div v-if="!sidebarCollapsed" class="logo-text">
            <span class="logo-name">Mate<span class="logo-name-highlight">Claw</span></span>
            <span class="logo-version">v{{ appVersion }}</span>
          </div>
        </transition>
        <button
          class="collapse-btn"
          :title="sidebarToggleLabel"
          :aria-label="sidebarToggleLabel"
          @click="toggleSidebar"
        >
          <svg v-if="!sidebarCollapsed" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
      </div>

      <!-- 导航菜单 -->
      <nav class="sidebar-nav">
        <template v-for="group in navGroups" :key="group.key">
          <div class="nav-group">
            <div v-if="!sidebarCollapsed" class="nav-group-title">{{ group.label }}</div>
            <router-link
              v-for="item in group.items"
              :key="item.path"
              :to="item.path"
              class="nav-item"
              :class="{ active: isNavItemActive(item) }"
              :title="sidebarCollapsed ? item.label : ''"
              @click="onNavClick"
            >
              <span class="nav-icon" v-html="item.icon"></span>
              <span v-if="!sidebarCollapsed" class="nav-label">{{ item.label }}</span>
            </router-link>
          </div>
        </template>
      </nav>

      <!-- 底部 -->
      <div class="sidebar-footer">
        <!-- Doctor 健康指示器 -->
        <button class="health-indicator" :class="healthStatus" @click="showDoctor = true" :title="t('doctor.title')">
          <span class="health-dot"></span>
          <span v-if="!sidebarCollapsed" class="health-label">{{ t('doctor.title') }}</span>
        </button>
        <!-- 主题切换 -->
        <div class="theme-toggle-row">
          <button
            v-for="opt in themeOptions"
            :key="opt.value"
            class="theme-btn"
            :class="{ active: themeStore.mode === opt.value }"
            :title="opt.label"
            @click="themeStore.setMode(opt.value)"
          >
            <span v-html="opt.icon"></span>
            <span v-if="!sidebarCollapsed" class="theme-btn-label">{{ opt.label }}</span>
          </button>
        </div>

        <div class="user-info">
          <div class="user-avatar">{{ userInitial }}</div>
          <transition name="fade">
            <div v-if="!sidebarCollapsed" class="user-detail">
              <div class="user-name">{{ username }}</div>
              <div class="user-role">{{ roleLabel }}</div>
            </div>
          </transition>
          <button v-if="!sidebarCollapsed" class="logout-btn" @click="logout" :title="t('nav.logout')">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
              <polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>
            </svg>
          </button>
        </div>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="main-content">
      <!-- 移动端顶部栏 -->
      <div v-if="isMobile" class="mobile-topbar">
        <button class="mobile-menu-btn" @click="mobileMenuOpen = true" :title="t('common.expandSidebar')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="3" y1="6" x2="21" y2="6"/>
            <line x1="3" y1="12" x2="21" y2="12"/>
            <line x1="3" y1="18" x2="21" y2="18"/>
          </svg>
        </button>
        <span class="mobile-topbar-title">Mate<span class="logo-name-highlight">Claw</span></span>
      </div>
      <router-view />
    </main>

    <OnboardingWizard v-if="showOnboarding" @close="showOnboarding = false" />
    <DoctorDrawer :visible="showDoctor" @close="showDoctor = false" @status="onHealthStatus" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useThemeStore } from '@/stores/useThemeStore'
import { version as appVersion } from '../../../package.json'
import type { ThemeMode } from '@/stores/useThemeStore'
import { http, setupApi } from '@/api/index'
import OnboardingWizard from '@/views/Onboarding/OnboardingWizard.vue'
import DoctorDrawer from '@/views/Doctor/DoctorDrawer.vue'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const themeStore = useThemeStore()
const sidebarCollapsed = ref(localStorage.getItem('mc-sidebar-collapsed') === 'true')
const showOnboarding = ref(false)
const showDoctor = ref(false)
const healthStatus = ref('unknown')

function onHealthStatus(status: string) {
  healthStatus.value = status
}

async function fetchHealthStatus() {
  try {
    const res: any = await http.get('/system/health')
    const data = res?.data || res
    healthStatus.value = data?.overall || 'healthy'
  } catch {
    healthStatus.value = 'unknown'
  }
}

// 移动端状态
const isMobile = ref(false)
const mobileMenuOpen = ref(false)
let mobileQuery: MediaQueryList | null = null

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = e.matches
  if (!e.matches) mobileMenuOpen.value = false
}

onMounted(async () => {
  mobileQuery = window.matchMedia('(max-width: 768px)')
  handleMobileChange(mobileQuery)
  mobileQuery.addEventListener('change', handleMobileChange)

  // Check onboarding status
  if (!localStorage.getItem('mc-onboarding-done')) {
    try {
      const res: any = await setupApi.onboardingStatus()
      if (res?.data && !res.data.hasDefaultModel) {
        showOnboarding.value = true
      }
    } catch {
      // If endpoint doesn't exist yet, skip onboarding
    }
  }

  // Fetch initial health status for sidebar indicator
  fetchHealthStatus()
})

onBeforeUnmount(() => {
  mobileQuery?.removeEventListener('change', handleMobileChange)
})

function onNavClick() {
  if (isMobile.value) mobileMenuOpen.value = false
}

const username = computed(() => localStorage.getItem('username') || 'User')
const role = computed(() => localStorage.getItem('role') || 'user')
const userInitial = computed(() => username.value.charAt(0).toUpperCase())
const roleLabel = computed(() => role.value === 'admin' ? t('nav.roleAdmin') : t('nav.roleUser'))
const sidebarToggleLabel = computed(() => sidebarCollapsed.value ? t('common.expandSidebar') : t('common.collapseSidebar'))

const themeOptions = computed<{ value: ThemeMode; label: string; icon: string }[]>(() => [
  {
    value: 'light',
    label: t('nav.themeLight'),
    icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>',
  },
  {
    value: 'dark',
    label: t('nav.themeDark'),
    icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>',
  },
  {
    value: 'system',
    label: t('nav.themeSystem'),
    icon: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>',
  },
])

const navGroups = computed(() => [
  {
    key: 'core',
    label: t('nav.core'),
    items: [
      {
        path: '/chat',
        label: t('nav.chat'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`,
      },
      {
        path: '/agents',
        label: t('nav.agents'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M20 21a8 8 0 1 0-16 0"/></svg>`,
      },
      {
        path: '/wiki',
        label: t('nav.wiki'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/><line x1="8" y1="7" x2="16" y2="7"/><line x1="8" y1="11" x2="14" y2="11"/></svg>`,
      },
    ],
  },
  {
    key: 'connect',
    label: t('nav.connect'),
    items: [
      {
        path: '/channels',
        label: t('nav.channels'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07A19.5 19.5 0 0 1 4.69 12a19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 3.6 1.18h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81a2 2 0 0 1-.45 2.11L7.91 8.73a16 16 0 0 0 6.29 6.29l1.62-1.62a2 2 0 0 1 2.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92z"/></svg>`,
      },
      {
        path: '/skills',
        label: t('nav.skills'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>`,
      },
      {
        path: '/tools',
        label: t('nav.tools'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>`,
      },
    ],
  },
  {
    key: 'system',
    label: t('nav.system'),
    items: [
      {
        path: '/settings/models',
        label: t('nav.settings'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M4.93 4.93a10 10 0 0 0 0 14.14"/></svg>`,
      },
      {
        path: '/security',
        label: t('nav.security'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
      },
    ],
  },
])

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  localStorage.setItem('mc-sidebar-collapsed', String(sidebarCollapsed.value))
}

function isNavItemActive(item: { path: string; label: string }) {
  if (item.path.startsWith('/settings')) {
    return route.path.startsWith('/settings')
  }
  if (item.path === '/security') {
    return route.path.startsWith('/security')
  }
  return route.path === item.path
}

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  router.push('/login')
}
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  background: var(--mc-bg);
  overflow: hidden;
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: 220px;
  min-width: 220px;
  background: var(--mc-sidebar-bg);
  border-right: 1px solid var(--mc-sidebar-border);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease, min-width 0.2s ease;
  overflow: hidden;
}

.sidebar.collapsed {
  width: 56px;
  min-width: 56px;
}

.sidebar-logo {
  display: flex;
  align-items: center;
  padding: 14px 12px;
  border-bottom: 1px solid var(--mc-border-light);
  gap: 10px;
  min-height: 58px;
}

.sidebar.collapsed .sidebar-logo {
  flex-direction: column;
  justify-content: center;
  padding: 10px 6px;
  gap: 8px;
  min-height: 88px;
}

.logo-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
}

.logo-img {
  width: 32px;
  height: 32px;
  object-fit: contain;
  filter: drop-shadow(0 2px 6px rgba(217, 119, 87, 0.25));
}

.logo-emoji { font-size: 16px; }

.logo-text { flex: 1; overflow: hidden; }

.logo-name {
  display: block;
  font-size: 15px;
  font-weight: 700;
  color: var(--mc-sidebar-logo-name);
  white-space: nowrap;
}

.logo-name-highlight {
  color: var(--mc-primary);
}

.logo-version {
  display: block;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}

.collapse-btn {
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  flex-shrink: 0;
  padding: 0;
  margin-left: auto;
}

.collapse-btn:hover {
  background: var(--mc-sidebar-hover);
  color: var(--mc-text-primary);
}

.sidebar.collapsed .collapse-btn {
  width: 32px;
  height: 32px;
  margin-left: 0;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  color: var(--mc-sidebar-text-active);
}

.sidebar.collapsed .collapse-btn:hover {
  background: var(--mc-sidebar-hover);
  border-color: var(--mc-border);
}

/* 导航 */
.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.sidebar-nav::-webkit-scrollbar { width: 4px; }
.sidebar-nav::-webkit-scrollbar-thumb { background: var(--mc-border); border-radius: 2px; }

.nav-group { margin-bottom: 2px; }

.nav-group-title {
  padding: 8px 16px 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-sidebar-group-title);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  white-space: nowrap;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  color: var(--mc-sidebar-text);
  text-decoration: none;
  font-size: 14px;
  border-radius: 6px;
  margin: 1px 8px;
  transition: all 0.15s ease;
  white-space: nowrap;
  overflow: hidden;
  position: relative;
}

.nav-item:hover {
  background: var(--mc-sidebar-hover);
  color: var(--mc-text-primary);
}

.nav-item.active {
  background: var(--mc-sidebar-active);
  color: var(--mc-sidebar-text-active);
  font-weight: 500;
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 4px;
  width: 3px;
  background: var(--mc-primary);
  border-radius: 0 3px 3px 0;
}

.nav-icon { display: flex; align-items: center; flex-shrink: 0; }
.nav-label { overflow: hidden; text-overflow: ellipsis; }

/* 底部 */
.sidebar-footer {
  border-top: 1px solid var(--mc-border-light);
  padding: 10px 12px;
}
.health-indicator { display: flex; align-items: center; gap: 8px; width: 100%; padding: 6px 8px; border: none; background: none; border-radius: 6px; cursor: pointer; color: var(--mc-text-secondary); font-size: 12px; margin-bottom: 6px; }
.health-indicator:hover { background: var(--mc-bg-sunken); }
.health-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.health-indicator.healthy .health-dot { background: var(--mc-success); }
.health-indicator.warning .health-dot { background: var(--mc-primary); }
.health-indicator.error .health-dot { background: var(--mc-danger); }
.health-indicator.unknown .health-dot { background: var(--mc-text-tertiary); }

/* 主题切换 */
.theme-toggle-row {
  display: flex;
  gap: 2px;
  background: var(--mc-bg-sunken);
  border-radius: 8px;
  padding: 3px;
  margin-bottom: 10px;
}

.theme-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 5px 4px;
  border: none;
  background: transparent;
  color: var(--mc-text-tertiary);
  border-radius: 6px;
  cursor: pointer;
  font-size: 11px;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.theme-btn:hover {
  color: var(--mc-text-secondary);
}

.theme-btn.active {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.theme-btn-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-avatar {
  width: 32px;
  height: 32px;
  background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover));
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.user-detail { flex: 1; overflow: hidden; }

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-role { font-size: 11px; color: var(--mc-text-tertiary); }

.logout-btn {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  padding: 0;
  flex-shrink: 0;
}

.logout-btn:hover {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}

/* ===== 主内容区 ===== */
.main-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* ===== 移动端元素（桌面端隐藏） ===== */
.sidebar-backdrop {
  display: none;
}

.mobile-topbar {
  display: none;
}

/* 动画 */
.fade-enter-active,
.fade-leave-active { transition: opacity 0.15s ease; }
.fade-enter-from,
.fade-leave-to { opacity: 0; }

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 1000;
    width: 260px;
    min-width: 260px;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: none;
  }

  .sidebar.mobile-open {
    transform: translateX(0);
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.15);
  }

  .sidebar.collapsed {
    width: 260px;
    min-width: 260px;
  }

  .collapse-btn {
    display: none;
  }

  .sidebar-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 999;
    background: rgba(0, 0, 0, 0.3);
  }

  .mobile-topbar {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 14px;
    background: var(--mc-sidebar-bg);
    border-bottom: 1px solid var(--mc-border-light);
    flex-shrink: 0;
  }

  .mobile-topbar-title {
    font-size: 16px;
    font-weight: 700;
    color: var(--mc-text-primary);
  }

  .mobile-menu-btn {
    width: 36px;
    height: 36px;
    border: 1px solid var(--mc-border);
    background: var(--mc-bg-elevated);
    border-radius: 8px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--mc-text-primary);
    flex-shrink: 0;
  }

  .mobile-menu-btn:hover {
    background: var(--mc-bg-sunken);
  }
}
</style>
