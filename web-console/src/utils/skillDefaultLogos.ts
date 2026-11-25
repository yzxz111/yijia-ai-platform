import fileManagerLogo from '../assets/skill-logos/file-manager.png'
import desktopControlLogo from '../assets/skill-logos/desktop-control.png'
import aihotNewsLogo from '../assets/skill-logos/aihot-news.png'
import figmaLogo from '../assets/skill-logos/figma.png'
import automationWorkflowsLogo from '../assets/skill-logos/automation-workflows.png'
import tavilySearchLogo from '../assets/skill-logos/tavily-search.png'
import agentTeamLogo from '../assets/skill-logos/agent-team.png'
import dailyTrendingLogo from '../assets/skill-logos/daily-trending.png'
import documentProLogo from '../assets/skill-logos/document-pro.png'
import chineseOfficialWritingLogo from '../assets/skill-logos/chinese-official-writing.png'
import multiPlatformContentLogo from '../assets/skill-logos/multi-platform-content.png'

/** Only catalog skills with bundled logos are mapped here. */
const DEFAULT_SKILL_LOGOS: Readonly<Record<string, string>> = {
  'russellfei-file-manager-b06ee3699daa': fileManagerLogo,
  'file-manager': fileManagerLogo,
  'matagul-desktop-control-891a9b41e92e': desktopControlLogo,
  'desktop-control': desktopControlLogo,
  'kkkkhazix-aihot-d1f2b4714045': aihotNewsLogo,
  'aihot': aihotNewsLogo,
  'maddiedreese-figma-deffbc10ec73': figmaLogo,
  'figma': figmaLogo,
  'jk-automation-workflows-b444b6f0a8a5': automationWorkflowsLogo,
  'automation-workflows': automationWorkflowsLogo,
  'jacky1n7-openclaw-tavily-search-5f8a7e9718f1': tavilySearchLogo,
  'openclaw-tavily-search': tavilySearchLogo,
  'arminnaimi-agent-team-orchestration-63936a511529': agentTeamLogo,
  'agent-team-orchestration': agentTeamLogo,
  'clawhub-daily-trending-39aee037ebbd': dailyTrendingLogo,
  'daily-trending': dailyTrendingLogo,
  'jackeven02-document-pro-fc17dd61013e': documentProLogo,
  'document-pro': documentProLogo,
  'gongyu0918-debug-chinese-official-writing-601f63b1c6a2': chineseOfficialWritingLogo,
  'debug-chinese-official-writing': chineseOfficialWritingLogo,
  'content-writer': multiPlatformContentLogo,
}

export function defaultSkillLogoForCode(code: string | null | undefined): string {
  return code && Object.hasOwn(DEFAULT_SKILL_LOGOS, code) ? DEFAULT_SKILL_LOGOS[code] : ''
}
