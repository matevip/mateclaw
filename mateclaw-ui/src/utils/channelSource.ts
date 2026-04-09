const SOURCE_LABELS: Record<string, string> = {
  web: 'Web',
  feishu: '飞书',
  dingtalk: '钉钉',
  telegram: 'Telegram',
  discord: 'Discord',
  wecom: '企业微信',
  weixin: '微信',
  qq: 'QQ',
  slack: 'Slack',
  cron: '定时任务',
}

const ICON_CHANNELS = ['web', 'feishu', 'dingtalk', 'telegram', 'discord', 'wecom', 'weixin', 'qq', 'slack', 'cron']

export function channelIconUrl(source?: string): string {
  const key = source || 'web'
  if (ICON_CHANNELS.includes(key)) return `/icons/channels/${key}.svg`
  return '/icons/channels/web.svg'
}

export function sourceLabel(source?: string): string {
  return SOURCE_LABELS[source || 'web'] || 'Web'
}
