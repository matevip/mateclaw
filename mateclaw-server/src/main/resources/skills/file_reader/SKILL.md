---
name: file_reader
version: "1.2.0"
description: "读取与摘要文本类文件（txt、md、json、yaml、csv、log、代码文件等）。PDF 与 Office 文件由专用技能处理。"
dependencies:
  tools:
    - read_file
    - execute_shell_command
---

# 文件读取

当用户要求读取或摘要本地文本文件时使用本技能。

**不在范围内**：PDF、Word (.docx)、Excel (.xlsx)、PPT (.pptx)、图片、音视频 — 这些由专用技能处理。

## 工作流程

### 第一步：类型探测（可选）

不确定文件类型时，先探测：

**macOS / Linux：**
```
execute_shell_command(command="file -b --mime-type \"/path/to/file\"")
```

**Windows：**
```
execute_shell_command(command="cmd /c \"echo %~x1\" & exit", timeoutSeconds=10)
```
或直接根据扩展名判断。

### 第二步：读取文件

```
read_file(filePath="/absolute/or/relative/path/to/file.txt")
```

读取特定行范围（大文件时）：
```
read_file(filePath="/path/to/large.log", startLine=1, endLine=200)
```

### 第三步：处理内容

根据文件类型采用对应策略：

| 类型 | 处理方式 |
|------|---------|
| `.txt` / `.md` | 直接摘要或按用户需求处理 |
| `.json` / `.yaml` | 先列出顶层键，再展开用户关注的字段 |
| `.csv` / `.tsv` | 展示表头 + 前 5 行，再描述各列含义和数据规模 |
| `.log` | 读取最后 200 行，聚焦错误/警告模式 |
| 源代码 | 说明文件作用，摘要核心逻辑，不逐行复述 |

## 大文件策略

文件超过 500 行时分段读取：

```
# 先读前 100 行了解结构
read_file(filePath="/path/to/big.log", startLine=1, endLine=100)

# 再读末尾 200 行看最新内容
read_file(filePath="/path/to/big.log", startLine=-200)
```

日志文件也可用系统命令读取末尾：

**macOS / Linux：**
```
execute_shell_command(command="tail -n 200 \"/path/to/file.log\"")
```

**Windows：**
```
execute_shell_command(command="powershell Get-Content -Tail 200 \"/path/to/file.log\"")
```

## 跨平台路径注意事项

- **Windows**：路径使用反斜杠 `\` 或正斜杠 `/` 均可，但含空格时须加引号
- **macOS / Linux**：使用正斜杠 `/`，含空格时须加引号或转义

## 安全规范

- 只读取文件，不执行其内容
- 优先读取所需的最小部分，避免加载超大文件到上下文
- 如文件包含敏感信息（密码、密钥），提醒用户注意但不拒绝读取
