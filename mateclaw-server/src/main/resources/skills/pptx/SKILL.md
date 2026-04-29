---
name: pptx
description: "Use this skill any time a .pptx file is involved in any way - as input, output, or both. This includes: creating slide decks, pitch decks, or presentations; reading, parsing, or extracting text from any .pptx file; editing, modifying, or updating existing presentations; combining or splitting slide files; working with templates, layouts, speaker notes, or comments. Trigger whenever the user mentions \"deck\", \"slides\", \"presentation\", or references a .pptx filename."
dependencies:
  commands:
    - python3
  tools:
    - skillScriptTool
    - skillFileTool
    - delegateToAgent
platforms:
  - macos
  - linux
  - windows
---

> **Important:** All `scripts/` paths are relative to this skill directory.
> Use `run_skill_script` tool to execute scripts, or run with: `cd {this_skill_dir} && python scripts/...`

# PPTX Skill

## Prerequisites

- **markitdown[pptx]**: text extraction from presentations
- **Pillow**: thumbnail grid generation
- **pptxgenjs** (`npm install -g pptxgenjs`): creating presentations from scratch
- **LibreOffice** (`soffice`): presentation-to-PDF conversion
- **pdftoppm** (poppler-utils): PDF-to-image conversion for thumbnail/visual workflows
- If `pdftoppm` is unavailable, a Python fallback path may use `pdf2image`.

## Quick Reference

| Task | Guide |
|------|-------|
| Read/analyze content | `python -m markitdown presentation.pptx` |
| Edit or create from template | Unpack → manipulate → pack workflow |
| Create from scratch | Use pptxgenjs (npm) |

---

## Reading Content

```bash
# Text extraction
python -m markitdown presentation.pptx

# Visual overview (thumbnail grid)
python scripts/thumbnail.py presentation.pptx

# Raw XML access
python scripts/office/unpack.py presentation.pptx unpacked/
```

---

## Editing Workflow

1. Analyze template with `thumbnail.py`
2. Unpack: `python scripts/office/unpack.py presentation.pptx unpacked/`
3. Add/remove slides: `python scripts/add_slide.py unpacked/ --source <slide_num>`
4. Edit XML content in `unpacked/ppt/slides/`
5. Clean orphans: `python scripts/clean.py unpacked/`
6. Pack: `python scripts/office/pack.py unpacked/ output.pptx --original presentation.pptx`

### Adding Slides

```bash
# Duplicate an existing slide
python scripts/add_slide.py unpacked/ --source 2

# Add from layout template
python scripts/add_slide.py unpacked/ --layout 1
```

### Cleaning Up

```bash
# Remove orphaned slides, unreferenced media, update content types
python scripts/clean.py unpacked/
```

### Creating Thumbnails

```bash
# Create thumbnail grid of all slides
python scripts/thumbnail.py presentation.pptx

# Customize output
python scripts/thumbnail.py presentation.pptx --output thumbs.png --cols 4
```

---

## Creating from Scratch

Use `pptxgenjs` (Node.js) when no template is available. Install: `npm install -g pptxgenjs`

---

## Design Ideas

**Don't create boring slides.** Plain bullets on a white background won't impress anyone.

### Before Starting

- **Pick a bold, content-informed color palette**: should feel designed for THIS topic
- **Dominance over equality**: One color should dominate (60-70%), with 1-2 supporting tones
- **Dark/light contrast**: Dark backgrounds for title + conclusion, light for content
- **Commit to a visual motif**: Pick ONE distinctive element and repeat it

### Color Palettes

| Theme | Primary | Secondary | Accent |
|-------|---------|-----------|--------|
| **Midnight Executive** | `1E2761` (navy) | `CADCFC` (ice blue) | `FFFFFF` (white) |
| **Forest & Moss** | `2C5F2D` (forest) | `97BC62` (moss) | `F5F5F5` (cream) |
| **Coral Energy** | `F96167` (coral) | `F9E795` (gold) | `2F3C7E` (navy) |
| **Warm Terracotta** | `B85042` (terracotta) | `E7E8D1` (sand) | `A7BEAE` (sage) |
| **Ocean Gradient** | `065A82` (deep blue) | `1C7293` (teal) | `21295C` (midnight) |
| **Charcoal Minimal** | `36454F` (charcoal) | `F2F2F2` (off-white) | `212121` (black) |
| **Teal Trust** | `028090` (teal) | `00A896` (seafoam) | `02C39A` (mint) |
| **Berry & Cream** | `6D2E46` (berry) | `A26769` (dusty rose) | `ECE2D0` (cream) |
| **Sage Calm** | `84B59F` (sage) | `69A297` (eucalyptus) | `50808E` (slate) |
| **Cherry Bold** | `990011` (cherry) | `FCF6F5` (off-white) | `2F3C7E` (navy) |

### For Each Slide

**Every slide needs a visual element** - image, chart, icon, or shape.

**Layout options:**
- Two-column (text left, illustration on right)
- Icon + text rows (icon in colored circle, bold header, description below)
- 2x2 or 2x3 grid
- Half-bleed image with content overlay

**Data display:**
- Large stat callouts (big numbers 60-72pt with small labels below)
- Comparison columns (before/after, pros/cons)
- Timeline or process flow (numbered steps, arrows)

### Typography

| Header Font | Body Font |
|-------------|-----------|
| Georgia | Calibri |
| Arial Black | Arial |
| Calibri | Calibri Light |
| Cambria | Calibri |
| Trebuchet MS | Calibri |

| Element | Size |
|---------|------|
| Slide title | 36-44pt bold |
| Section header | 20-24pt bold |
| Body text | 14-16pt |
| Captions | 10-12pt muted |

### Spacing

- 0.5" minimum margins
- 0.3-0.5" between content blocks
- Leave breathing room

### Avoid (Common Mistakes)

- Don't repeat the same layout across slides
- Don't center body text - left-align paragraphs and lists
- Don't skimp on size contrast
- Don't default to blue - pick topic-appropriate colors
- Don't create text-only slides - add visual elements
- Don't forget text box padding
- NEVER use accent lines under titles - hallmark of AI-generated slides

---

## QA (Required)

**Assume there are problems. Your job is to find them.**

### Content QA

```bash
python -m markitdown output.pptx
```

Check for missing content, typos, wrong order. Check for leftover placeholder text:

```bash
python -m markitdown output.pptx | grep -iE "xxxx|lorem|ipsum"
```

### Visual QA

Convert slides to images, then inspect:

```bash
python scripts/office/soffice.py --headless --convert-to pdf output.pptx
pdftoppm -jpeg -r 150 output.pdf slide
```

Look for: overlapping elements, text overflow, low-contrast text, uneven gaps, insufficient margins.

### Subagent Visual QA (Fresh Eyes)

For high-stakes presentations, delegate a visual inspection to a separate agent that has NOT seen the creation process. A fresh pair of eyes catches issues the author missed.

```
delegateToAgent(
  agentName="strong-agent",
  task="[Visual QA Request] Inspect the attached presentation slides as a fresh reviewer.
You have no context about how these were made — treat it as if seeing them for the first time.

Slides location: <path to slide images or pptx>

Check for:
1. Any slide where text is cut off or overflows the frame
2. Low contrast (e.g., light text on light background)
3. Repeated layouts — more than 2 slides with identical structure
4. Text-only slides with no visual element
5. Accent lines under slide titles (hallmark of AI-generated slides)
6. Any leftover placeholder text (XXXX, lorem, [insert here])
7. Font size below 14pt in body text

For each issue, state: slide number, issue type, what you see.
If everything looks clean, say so explicitly."
)
```

Act on the subagent's findings before declaring the presentation complete.

### Verification Loop

1. Generate slides -> Convert to images -> Inspect
2. List issues found
3. Fix issues
4. Re-verify affected slides
5. Repeat until clean
6. (High-stakes) Run subagent visual QA for a fresh-eyes check

---

## Converting to Images

```bash
python scripts/office/soffice.py --headless --convert-to pdf output.pptx
pdftoppm -jpeg -r 150 output.pdf slide
```

Creates `slide-01.jpg`, `slide-02.jpg`, etc.
