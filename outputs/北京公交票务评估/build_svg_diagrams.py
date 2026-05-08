from pathlib import Path

OUT = Path("/Users/mate/Codes/mate/mateclaw/outputs/北京公交票务评估/svg设计图")
OUT.mkdir(parents=True, exist_ok=True)

COLORS = {
    "red": "#D71920",
    "wall_red": "#B63A2E",
    "gray": "#6F7378",
    "dark": "#1F2933",
    "light": "#F5F7FA",
    "line": "#D8DEE6",
    "blue": "#2E86C1",
    "green": "#3FA45B",
    "yellow": "#F2C94C",
    "ink": "#0B2545",
}


def write(name: str, body: str) -> None:
    (OUT / name).write_text(body, encoding="utf-8")


def header(title: str, subtitle: str, width=1440, height=960) -> str:
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <defs>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%">
      <feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#1F2933" flood-opacity="0.12"/>
    </filter>
    <marker id="arrow-red" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto">
      <path d="M0,0 L10,5 L0,10 Z" fill="{COLORS['red']}"/>
    </marker>
    <marker id="arrow-gray" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto">
      <path d="M0,0 L10,5 L0,10 Z" fill="{COLORS['gray']}"/>
    </marker>
    <style>
      .title {{ font: 700 34px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['ink']}; }}
      .subtitle {{ font: 400 17px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['gray']}; }}
      .h {{ font: 700 20px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['ink']}; }}
      .t {{ font: 400 15px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['dark']}; }}
      .s {{ font: 400 13px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['gray']}; }}
      .tiny {{ font: 400 12px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['gray']}; }}
      .card {{ fill: white; stroke: {COLORS['line']}; stroke-width: 1.2; filter: url(#shadow); }}
      .band {{ fill: {COLORS['light']}; stroke: {COLORS['line']}; stroke-width: 1; }}
      .redline {{ stroke: {COLORS['red']}; stroke-width: 3; fill: none; marker-end: url(#arrow-red); }}
      .grayline {{ stroke: {COLORS['gray']}; stroke-width: 2.2; fill: none; marker-end: url(#arrow-gray); }}
      .dash {{ stroke: {COLORS['gray']}; stroke-width: 2; stroke-dasharray: 8 8; fill: none; marker-end: url(#arrow-gray); }}
    </style>
  </defs>
  <rect width="100%" height="100%" fill="#FFFFFF"/>
  <path d="M0 0 H1440 V10 H0 Z" fill="{COLORS['red']}"/>
  <path d="M0 10 H1440 V15 H0 Z" fill="{COLORS['gray']}" opacity="0.55"/>
  <text x="64" y="72" class="title">{title}</text>
  <text x="64" y="102" class="subtitle">{subtitle}</text>
'''


def footer() -> str:
    return f'''
  <text x="64" y="925" class="tiny">北京公交集团票务综合管理平台系统升级 | SDD（Spec-Driven Development，规范驱动开发）实施方案配套设计图</text>
  <circle cx="1358" cy="904" r="18" fill="{COLORS['red']}" opacity="0.95"/>
  <circle cx="1395" cy="904" r="18" fill="{COLORS['gray']}" opacity="0.78"/>
</svg>
'''


def card(x, y, w, h, title, lines, color="#D71920"):
    parts = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" class="card"/>',
             f'<rect x="{x}" y="{y}" width="{w}" height="8" rx="4" fill="{color}"/>',
             f'<text x="{x+22}" y="{y+42}" class="h">{title}</text>']
    yy = y + 72
    for line in lines:
        parts.append(f'<text x="{x+22}" y="{yy}" class="t">{line}</text>')
        yy += 26
    return "\n".join(parts)


architecture = header("总体技术架构图", "从用户访问、应用服务、数据存储、外部集成到运维保障的分层架构")
architecture += f'''
  <rect x="58" y="138" width="1324" height="680" rx="18" class="band"/>
  <text x="92" y="178" class="h">访问与用户层</text>
  {card(88, 206, 248, 138, "集团用户", ["集团管理员 / 操作员", "全局管理、报表、审批"], COLORS['red'])}
  {card(376, 206, 248, 138, "分公司用户", ["分公司管理员 / 操作员", "本公司业务、审核、统计"], COLORS['blue'])}
  {card(664, 206, 248, 138, "票务室用户", ["票柜、票袋、票单", "配票、结算、退票"], COLORS['green'])}
  {card(952, 206, 248, 138, "移动/内网终端", ["浏览器访问", "打印、导入、导出"], COLORS['yellow'])}

  <path d="M212 356 V410" class="redline"/>
  <path d="M500 356 V410" class="redline"/>
  <path d="M788 356 V410" class="redline"/>
  <path d="M1076 356 V410" class="redline"/>

  <text x="92" y="438" class="h">应用与业务服务层</text>
  {card(88, 466, 248, 168, "前端应用", ["Vue3 / TypeScript", "菜单导航、表单、报表", "打印与导入导出"], COLORS['red'])}
  {card(376, 466, 248, 168, "网关与认证", ["统一登录认证", "菜单 / 按钮权限", "数据范围拦截"], COLORS['gray'])}
  {card(664, 466, 248, 168, "业务服务", ["票库、票柜、票单", "充值、无人售、退票", "审批与状态机"], COLORS['blue'])}
  {card(952, 466, 248, 168, "报表服务", ["日结、月结、趋势", "Excel 导出", "汇总表加速"], COLORS['green'])}

  <path d="M336 550 H370" class="grayline"/>
  <path d="M624 550 H658" class="grayline"/>
  <path d="M912 550 H946" class="grayline"/>
  <path d="M788 646 V698" class="redline"/>

  <text x="92" y="724" class="h">数据、集成与运维层</text>
  {card(88, 752, 220, 120, "达梦数据库", ["主业务库 / 主备", "库存、流水、报表"], COLORS['red'])}
  {card(342, 752, 220, 120, "Redis / 缓存", ["会话、字典、短期任务", "热点查询缓存"], COLORS['gray'])}
  {card(596, 752, 220, 120, "文件与对象存储", ["模板、导入文件", "导出报表、附件"], COLORS['yellow'])}
  {card(850, 752, 220, 120, "外部系统集成", ["IC卡 / 数据湖 / 胆款", "银行清分线下导入"], COLORS['blue'])}
  {card(1104, 752, 220, 120, "运维保障", ["日志、监控、备份", "灰度、回滚、巡检"], COLORS['green'])}
'''
architecture += footer()
write("01-总体技术架构图.svg", architecture)


flow = header("核心票务业务流转图", "围绕票库、票柜、票袋、票单、结算、退票和报表的主业务闭环")
flow += f'''
  <rect x="74" y="150" width="1290" height="700" rx="18" class="band"/>
  {card(104, 190, 220, 126, "1. 印制入库", ["分公司发起印制申请", "集团审核", "确认后进入票库"], COLORS['red'])}
  {card(384, 190, 220, 126, "2. 票库管理", ["票号段入库", "库存余额与流水", "调拨 / 核销 / 调账"], COLORS['gray'])}
  {card(664, 190, 220, 126, "3. 配票申请", ["票务室申请", "分公司确认", "票库到票柜"], COLORS['blue'])}
  {card(944, 190, 220, 126, "4. 票柜库存", ["票务室库存", "入库 / 出库查询", "票务室调票"], COLORS['green'])}

  <path d="M324 253 H378" class="redline"/>
  <path d="M604 253 H658" class="redline"/>
  <path d="M884 253 H938" class="redline"/>

  {card(104, 400, 220, 126, "5. 票袋管理", ["绑定线路、售票员", "更换线路", "票袋库存归属"], COLORS['green'])}
  {card(384, 400, 220, 126, "6. 票单配票", ["加载票袋数据", "录入起号止号", "票柜出库到票袋"], COLORS['red'])}
  {card(664, 400, 220, 126, "7. 票单结算", ["录入剩余票号", "自动计算张数金额", "生成结算记录"], COLORS['blue'])}
  {card(944, 400, 220, 126, "8. 票单查询", ["配票单 / 结算单", "打印、导出", "重新结算入口"], COLORS['gray'])}

  <path d="M1054 328 C1054 362 260 362 214 394" class="grayline"/>
  <path d="M324 463 H378" class="redline"/>
  <path d="M604 463 H658" class="redline"/>
  <path d="M884 463 H938" class="redline"/>

  {card(104, 610, 220, 126, "9. 退票处理", ["票袋退票", "票柜退票", "库存回写"], COLORS['wall_red'])}
  {card(384, 610, 220, 126, "10. 无人售/充值", ["无人售线路结算", "IC卡库存与收入", "导入与异常处理"], COLORS['yellow'])}
  {card(664, 610, 220, 126, "11. 报表汇总", ["日结、月结", "收入、清分、趋势", "三级数据权限"], COLORS['blue'])}
  {card(944, 610, 220, 126, "12. 审计追溯", ["库存流水", "操作日志", "票号查询"], COLORS['gray'])}

  <path d="M214 538 V604" class="grayline"/>
  <path d="M774 538 V604" class="redline"/>
  <path d="M884 673 H938" class="grayline"/>
  <path d="M1054 598 C1054 568 1054 560 1054 538" class="dash"/>
  <text x="1178" y="557" class="s">异常更正 / 重新结算</text>
'''
flow += footer()
write("02-核心票务业务流转图.svg", flow)


deployment = header("部署与可运维性架构图", "面向国产化环境的部署、监控、备份、回滚和运维交接设计")
deployment += f'''
  <rect x="70" y="148" width="1300" height="704" rx="18" class="band"/>
  <text x="102" y="188" class="h">网络与访问区</text>
  {card(104, 214, 230, 116, "用户浏览器", ["集团 / 分公司 / 票务室", "内网访问、统一认证"], COLORS['red'])}
  {card(394, 214, 230, 116, "Nginx / 网关", ["HTTPS 终止", "反向代理、限流"], COLORS['gray'])}
  {card(684, 214, 230, 116, "应用服务 A", ["业务 API", "前端静态资源"], COLORS['blue'])}
  {card(974, 214, 230, 116, "应用双节点预留", ["建议位 / 二期可选", "原需求为 1 台业务主机"], COLORS['gray'])}
  <path d="M334 272 H388" class="redline"/>
  <path d="M624 272 H678" class="redline"/>
  <path d="M914 272 H968" class="grayline"/>

  <text x="102" y="398" class="h">数据与文件区</text>
  {card(104, 426, 230, 128, "达梦主库", ["业务数据写入", "库存与单据主数据"], COLORS['red'])}
  {card(394, 426, 230, 128, "达梦备库", ["主备同步", "故障切换准备"], COLORS['gray'])}
  {card(684, 426, 230, 128, "Redis", ["会话、缓存", "任务状态、热点字典"], COLORS['green'])}
  {card(974, 426, 230, 128, "对象存储", ["模板、导入文件", "导出报表、附件"], COLORS['yellow'])}
  <rect x="1226" y="426" width="112" height="128" rx="10" fill="#FFFFFF" stroke="{COLORS['line']}" stroke-dasharray="6 6"/>
  <text x="1246" y="464" class="s">Oracle</text>
  <text x="1246" y="488" class="s">旧库</text>
  <text x="1246" y="512" class="tiny">迁移演练</text>
  <text x="1246" y="534" class="tiny">回滚备份</text>
  <path d="M1220 490 H630" class="dash"/>
  <path d="M334 490 H388" class="grayline"/>
  <path d="M799 342 V420" class="redline"/>
  <path d="M1089 342 V420" class="grayline"/>

  <text x="102" y="620" class="h">运维保障区</text>
  {card(104, 648, 216, 124, "日志中心", ["业务日志、接口日志", "登录与操作审计"], COLORS['gray'])}
  {card(360, 648, 216, 124, "监控告警", ["存活、慢SQL、磁盘", "异常率、任务失败"], COLORS['red'])}
  {card(616, 648, 216, 124, "备份恢复", ["数据库全量/增量", "文件备份、恢复演练"], COLORS['green'])}
  {card(872, 648, 216, 124, "CI/CD", ["构建、测试、制品", "部署、版本标记"], COLORS['blue'])}
  {card(1128, 648, 216, 124, "回滚预案", ["旧版本保留", "割接窗口、冒烟检查"], COLORS['wall_red'])}
  <path d="M684 592 C550 610 465 620 468 642" class="dash"/>
  <path d="M914 592 C790 610 724 622 724 642" class="dash"/>
  <path d="M624 272 C520 340 475 380 480 420" class="dash"/>
'''
deployment += footer()
write("03-部署与可运维性架构图.svg", deployment)


permissions = header("权限与数据范围设计图", "菜单权限、按钮权限、接口权限、数据权限和导出权限的统一控制")
permissions += f'''
  <rect x="70" y="148" width="1300" height="704" rx="18" class="band"/>
  {card(96, 198, 240, 134, "集团管理员", ["配置菜单、角色、用户", "分配分公司数据范围", "查看全集团数据"], COLORS['red'])}
  {card(396, 198, 240, 134, "集团操作员", ["按角色使用功能", "查看授权公司数据", "集团级报表"], COLORS['gray'])}
  {card(696, 198, 240, 134, "分公司管理员", ["维护本公司用户", "配置票务室权限", "本公司业务管理"], COLORS['blue'])}
  {card(996, 198, 240, 134, "票务室操作员", ["票柜、票袋、票单", "结算、退票、导出", "仅本票务室数据"], COLORS['green'])}

  <rect x="170" y="414" width="1100" height="100" rx="12" fill="#FFFFFF" stroke="{COLORS['line']}" filter="url(#shadow)"/>
  <text x="212" y="456" class="h">统一权限拦截层</text>
  <text x="212" y="486" class="t">菜单权限 → 按钮权限 → 接口权限 → 数据范围权限 → 导出/打印权限</text>
  <path d="M216 344 V408" class="redline"/>
  <path d="M516 344 V408" class="grayline"/>
  <path d="M816 344 V408" class="grayline"/>
  <path d="M1116 344 V408" class="grayline"/>

  {card(116, 604, 230, 126, "组织维度", ["集团", "分公司", "票务室 / 车队 / 线路"], COLORS['red'])}
  {card(386, 604, 230, 126, "业务维度", ["票库 / 票柜 / 票袋", "充值网点", "无人售线路"], COLORS['blue'])}
  {card(656, 604, 230, 126, "数据维度", ["单据、库存、流水", "报表汇总", "历史迁移数据"], COLORS['green'])}
  {card(926, 604, 230, 126, "审计维度", ["登录日志", "操作日志", "导入导出日志"], COLORS['gray'])}
  <path d="M720 524 V598" class="redline"/>
  <path d="M720 524 C530 548 500 570 500 598" class="grayline"/>
  <path d="M720 524 C902 548 1040 570 1040 598" class="grayline"/>
'''
permissions += footer()
write("04-权限与数据范围设计图.svg", permissions)


delivery = header("SDD 协同交付流程图", "把大系统拆成可验证的小任务，形成需求、编码、测试、评审、交付闭环")
delivery += f'''
  <rect x="74" y="148" width="1290" height="704" rx="18" class="band"/>
  {card(102, 202, 230, 128, "1. 需求卡片化", ["按页面/动作拆分", "角色、输入、输出", "验收标准"], COLORS['red'])}
  {card(382, 202, 230, 128, "2. 领域建模", ["状态机、票号段", "库存流水、报表口径", "人工确认"], COLORS['gray'])}
  {card(662, 202, 230, 128, "3. 任务生成", ["表结构、接口", "页面、测试", "小批量执行"], COLORS['blue'])}
  {card(942, 202, 230, 128, "4. 编码实现", ["前后端协同", "导入导出", "权限与日志"], COLORS['green'])}
  <path d="M332 266 H376" class="redline"/>
  <path d="M612 266 H656" class="redline"/>
  <path d="M892 266 H936" class="redline"/>

  {card(102, 470, 230, 128, "8. 模块验收", ["业务演示", "样例数据核对", "问题闭环"], COLORS['wall_red'])}
  {card(382, 470, 230, 128, "7. 人工评审", ["核心规则 review", "安全与权限检查", "SQL 与事务检查"], COLORS['gray'])}
  {card(662, 470, 230, 128, "6. 自动化测试", ["单元 / 接口", "场景 / 回归", "构建检查"], COLORS['blue'])}
  {card(942, 470, 230, 128, "5. 本地自测", ["页面联调", "异常分支", "导入导出校验"], COLORS['green'])}
  <path d="M1057 342 V464" class="redline"/>
  <path d="M942 534 H898" class="grayline"/>
  <path d="M662 534 H618" class="grayline"/>
  <path d="M382 534 H338" class="grayline"/>

  <rect x="208" y="690" width="944" height="74" rx="12" fill="#FFFFFF" stroke="{COLORS['line']}" filter="url(#shadow)"/>
  <text x="238" y="726" class="h">交付原则</text>
  <text x="362" y="724" class="t">小任务、强测试、人工把关、持续演示；常规功能提效，核心规则不省评审。</text>
  <path d="M217 608 C217 670 678 650 678 684" class="dash"/>
'''
delivery += footer()
write("05-SDD协同交付流程图.svg", delivery)


lake = header("数据湖与外部系统对接架构图", "明确票务系统向数据湖、二维码、胆款、IC卡和银行清分场景的数据供给边界")
lake += f'''
  <rect x="72" y="148" width="1292" height="704" rx="18" class="band"/>
  <text x="104" y="190" class="h">票务系统数据源</text>
  {card(104, 222, 222, 124, "业务库", ["单据、库存、流水", "结算、退票、充值"], COLORS['red'])}
  {card(370, 222, 222, 124, "报表汇总库", ["日结、月结、趋势", "集团 / 分公司 / 票务室"], COLORS['blue'])}
  {card(636, 222, 222, 124, "文件与附件", ["导入文件", "导出报表、签章文件"], COLORS['yellow'])}

  <text x="104" y="426" class="h">数据服务与交换层</text>
  {card(104, 458, 222, 136, "数据目录", ["数据集清单", "字段字典", "版本管理"], COLORS['gray'])}
  {card(370, 458, 222, 136, "接口服务", ["REST / 批量文件", "鉴权、限流、加密"], COLORS['red'])}
  {card(636, 458, 222, 136, "同步任务", ["全量 / 增量", "重试、补偿、对账"], COLORS['green'])}
  {card(902, 458, 222, 136, "接口审计", ["调用日志", "失败告警", "手工补传"], COLORS['blue'])}

  <path d="M215 358 V452" class="redline"/>
  <path d="M481 358 V452" class="redline"/>
  <path d="M747 358 V452" class="grayline"/>
  <path d="M326 526 H364" class="grayline"/>
  <path d="M592 526 H630" class="grayline"/>
  <path d="M858 526 H896" class="grayline"/>

  <text x="104" y="670" class="h">外部消费与对接对象</text>
  {card(104, 700, 188, 116, "数据湖", ["统一取数", "数据质量校验"], COLORS['red'])}
  {card(326, 700, 188, 116, "二维码系统", ["是否保留供数", "历史数据确认"], COLORS['gray'])}
  {card(548, 700, 188, 116, "胆款系统", ["清分日报同步", "对账与补偿"], COLORS['blue'])}
  {card(770, 700, 188, 116, "IC卡系统", ["主数据同步", "增量补偿"], COLORS['green'])}
  {card(992, 700, 168, 116, "银行清分", ["线下文件导入", "异常明细"], COLORS['yellow'])}
  {card(1190, 700, 150, 116, "电子签章", ["状态预留", "签章归档"], COLORS['wall_red'])}

  <path d="M481 606 C400 638 220 650 198 694" class="redline"/>
  <path d="M481 606 C455 646 430 666 420 694" class="grayline"/>
  <path d="M747 606 C705 646 662 666 642 694" class="grayline"/>
  <path d="M747 606 C820 640 850 662 864 694" class="grayline"/>
  <path d="M1013 606 C1070 642 1085 666 1086 694" class="grayline"/>
  <path d="M1013 606 C1180 642 1240 666 1265 694" class="grayline"/>
'''
lake += footer()
write("06-数据湖与外部系统对接架构图.svg", lake)


map_svg = header("系统功能地图", "按集团、分公司、票务室三级用户视角展示业务入口和能力分布")
map_svg += f'''
  <rect x="72" y="148" width="1292" height="704" rx="18" class="band"/>
  <text x="104" y="190" class="h">集团视角</text>
  {card(104, 220, 210, 116, "集团管理", ["用户、权限、菜单", "阈值、日志、配置"], COLORS['red'])}
  {card(344, 220, 210, 116, "集团审批", ["印制、调拨、核销", "调账审核"], COLORS['gray'])}
  {card(584, 220, 210, 116, "集团报表", ["存售、收入、清分", "趋势、月报"], COLORS['blue'])}
  {card(824, 220, 210, 116, "数据服务", ["数据湖、二维码", "接口审计"], COLORS['green'])}

  <text x="104" y="414" class="h">分公司视角</text>
  {card(104, 444, 210, 116, "基础资料", ["票务室、线路", "车辆、司售、票价"], COLORS['blue'])}
  {card(344, 444, 210, 116, "票库票柜", ["印制、调拨、核销", "配票申请、调票"], COLORS['red'])}
  {card(584, 444, 210, 116, "充值与特殊", ["IC卡库存、售出", "异物、大额币、残币"], COLORS['yellow'])}
  {card(824, 444, 210, 116, "分公司报表", ["日报、月报", "收入核对、导出"], COLORS['green'])}

  <text x="104" y="638" class="h">票务室视角</text>
  {card(104, 668, 210, 116, "票柜库存", ["入库、出库", "库存查询"], COLORS['green'])}
  {card(344, 668, 210, 116, "票袋票单", ["票袋、配票", "结算、重新结算"], COLORS['red'])}
  {card(584, 668, 210, 116, "退票打印", ["票袋/票柜退票", "配票单、结算单"], COLORS['gray'])}
  {card(824, 668, 210, 116, "票务室报表", ["日结、存售", "交班、银行登记"], COLORS['blue'])}

  <rect x="1090" y="220" width="190" height="564" rx="12" fill="#FFFFFF" stroke="{COLORS['line']}" filter="url(#shadow)"/>
  <rect x="1090" y="220" width="190" height="8" rx="4" fill="{COLORS['red']}"/>
  <text x="1116" y="262" class="h">共用能力</text>
  <text x="1116" y="306" class="t">消息中心</text>
  <text x="1116" y="338" class="t">阈值预警</text>
  <text x="1116" y="370" class="t">Excel 导入导出</text>
  <text x="1116" y="402" class="t">打印模板</text>
  <text x="1116" y="434" class="t">操作审计</text>
  <text x="1116" y="466" class="t">数据权限</text>
  <text x="1116" y="498" class="t">外部接口</text>
  <text x="1116" y="530" class="t">备份恢复</text>
  <path d="M1038 278 H1084" class="grayline"/>
  <path d="M1038 502 H1084" class="grayline"/>
  <path d="M1038 726 H1084" class="grayline"/>
'''
map_svg += footer()
write("07-系统功能地图.svg", map_svg)

index = "\n".join([
    "# SVG 设计图清单",
    "",
    "- 01-总体技术架构图.svg",
    "- 02-核心票务业务流转图.svg",
    "- 03-部署与可运维性架构图.svg",
    "- 04-权限与数据范围设计图.svg",
    "- 05-SDD协同交付流程图.svg",
    "- 06-数据湖与外部系统对接架构图.svg",
    "- 07-系统功能地图.svg",
])
(OUT / "README.md").write_text(index, encoding="utf-8")
print(OUT)
