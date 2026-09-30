#!/usr/bin/env node
'use strict'

/**
 * Builds the branded documentation PDF from the Markdown guides in docs/.
 *
 * Pipeline
 * ────────
 * pandoc converts each guide to HTML. The chapters are stitched into a single
 * document behind a cover and a contents page, and headless Chrome prints it.
 * Chrome is driven over the DevTools protocol rather than `--print-to-pdf` so
 * printing can wait until the Mermaid diagrams have rendered, and so the PDF
 * gets a bookmark outline.
 *
 * Every heading id is prefixed with its chapter, so "Troubleshooting" in two
 * guides stays two anchors, and links between guides resolve inside the PDF.
 * Links to files that are not part of the PDF point at the repository on GitHub.
 *
 * Requirements
 * ────────────
 *   pandoc          on PATH
 *   Google Chrome   default macOS location, or set CHROME_PATH
 *   network         Mermaid diagrams and the Inter typeface load from CDNs;
 *                   offline, diagrams fall back to their source text
 *
 * Usage
 * ─────
 *   npm run docs:pdf
 *   node scripts/docs-pdf/build.js [--out <file.pdf>] [--keep-html]
 */

const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync, spawn } = require('child_process')
const { pathToFileURL } = require('url')

const ROOT = path.resolve(__dirname, '..', '..')
const DOCS_DIR = path.join(ROOT, 'docs')
const LOGO_PATH = path.join(DOCS_DIR, 'assets/algolia-logo.svg')
const REPO_BLOB_URL = 'https://github.com/algolia/algolia-aem-extensions/blob/main'
const MERMAID_URL = 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js'
const FONTS_URL = 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap'
const RENDER_TIMEOUT_MS = 180000

const CHROME_CANDIDATES = [
    process.env.CHROME_PATH,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser'
]

const CHAPTERS = [
    {
        id: 'installation',
        file: 'INSTALLATION.md',
        title: 'Installation Guide',
        blurb: 'Prerequisites, building the extensions package, deploying it to AEM, and running tests.'
    },
    {
        id: 'pdf-text',
        file: 'PDF_TEXT.md',
        title: 'PDF Text Extractor',
        blurb: 'Extracting PDF text into Algolia records, the word-size limit, and splitting records that exceed the size threshold.'
    },
    {
        id: 'tags',
        file: 'TAGS.md',
        title: 'Tags Extractor',
        blurb: 'Adding cq:tags from pages and assets onto Algolia records with the default tags extractor.'
    },
    {
        id: 'custom',
        file: 'CUSTOM_EXTENSIONS.md',
        title: 'Building Custom Extensions',
        blurb: 'Implementing page and asset request extenders, registering them as OSGi services, and selecting them on a Cloud Service configuration.'
    }
]

function parseArgs(argv) {
    const args = { out: null, keepHtml: false }
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--out') args.out = path.resolve(argv[++i])
        else if (argv[i] === '--keep-html') args.keepHtml = true
        else throw new Error(`Unknown argument: ${argv[i]}`)
    }
    return args
}

function escapeHtml(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function stripTags(html) {
    return html.replace(/<[^>]+>/g, '')
}

// ── Markdown → chapter HTML ─────────────────────────────────────────────────

/**
 * Drops the guide's own H1 and its "Table of contents" block. The chapter
 * banner replaces the first, and the PDF's contents page replaces the second.
 */
function prepareMarkdown(markdown) {
    return markdown
        .replace(/^# .*\n/, '')
        .replace(/^## Table of contents\n[\s\S]*?^---[ \t]*$/m, '')
}

function renderMarkdown(markdown) {
    return execFileSync('pandoc', ['-f', 'gfm', '-t', 'html5', '--wrap=none'], {
        input: markdown,
        encoding: 'utf8',
        maxBuffer: 64 * 1024 * 1024
    })
}

function resolveHref(href, chapter, chapterByPath) {
    if (/^(https?:|mailto:)/i.test(href)) return href
    if (href.startsWith('#')) return `#${chapter.id}--${href.slice(1)}`

    const [target, fragment] = href.split('#')
    const absolute = path.resolve(DOCS_DIR, decodeURIComponent(target))
    const linked = chapterByPath.get(absolute)
    if (linked) return fragment ? `#${linked.id}--${fragment}` : `#chapter-${linked.id}`

    const repoPath = path.relative(ROOT, absolute).split(path.sep).join('/')
    return `${REPO_BLOB_URL}/${repoPath}${fragment ? `#${fragment}` : ''}`
}

function resolveImageSrc(src) {
    if (/^(https?:|data:|file:)/i.test(src)) return src
    return pathToFileURL(path.resolve(DOCS_DIR, decodeURIComponent(src))).href
}

function rewriteChapterHtml(html, chapter, chapterByPath) {
    return html
        // Pandoc's per-line anchors in code blocks are noise in print.
        .replace(/<a href="#cb\d+-\d+"[^>]*><\/a>/g, '')
        .replace(/<pre class="mermaid"><code>([\s\S]*?)<\/code><\/pre>/g, '<div class="mermaid">$1</div>')
        .replace(/<p><img src="([^"]+)" alt="([^"]*)" \/><\/p>/g,
            '<figure><img src="$1" alt="$2" /><figcaption>$2</figcaption></figure>')
        .replace(/ id="([^"]+)"/g, (_, id) => ` id="${chapter.id}--${id}"`)
        .replace(/href="([^"]*)"/g, (_, href) => `href="${resolveHref(href, chapter, chapterByPath)}"`)
        .replace(/src="([^"]*)"/g, (_, src) => `src="${resolveImageSrc(src)}"`)
}

function collectSections(html) {
    return [...html.matchAll(/<h2 id="([^"]+)">([\s\S]*?)<\/h2>/g)]
        .map(([, id, inner]) => ({ id, title: stripTags(inner).trim() }))
}

function buildChapter(chapter, index, chapterByPath) {
    const markdown = fs.readFileSync(path.join(DOCS_DIR, chapter.file), 'utf8')
    const body = rewriteChapterHtml(renderMarkdown(prepareMarkdown(markdown)), chapter, chapterByPath)
    const number = String(index + 1).padStart(2, '0')
    const html = `
<section class="chapter" id="chapter-${chapter.id}">
  <header class="chapter-banner">
    <div class="chapter-num">Part ${number}</div>
    <h1 class="chapter-title">${escapeHtml(chapter.title)}</h1>
    <p class="chapter-blurb">${escapeHtml(chapter.blurb)}</p>
  </header>
  ${body}
</section>`
    return { ...chapter, number, html, sections: collectSections(body) }
}

// ── Cover and contents ──────────────────────────────────────────────────────

function readLogos() {
    const svg = fs.readFileSync(LOGO_PATH, 'utf8').replace(/^<\?xml[^>]*\?>/, '')
    const white = svg.replace(/#003dff/gi, '#ffffff')
    const markPath = svg.match(/<path class="cls-1" d="(M249\.83[^"]+)"/)
    const mark = markPath
        ? `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 500 500"><path fill="#003dff" d="${markPath[1]}"/></svg>`
        : ''
    return { white, mark }
}

function buildCover({ version, published, whiteLogo }) {
    const parts = CHAPTERS.map(c => escapeHtml(c.title.replace(' Guide', ''))).join(' · ')
    return `
<section class="cover">
  <div class="cover-logo">${whiteLogo}</div>
  <div class="cover-body">
    <div class="cover-eyebrow">Adobe Experience Manager</div>
    <h1 class="cover-title">Algolia AEM Extensions</h1>
    <p class="cover-subtitle">Reference indexing extensions for PDF text and tags, and a guide to building page and asset request extenders for the Algolia Connector for AEM.</p>
  </div>
  <div class="cover-meta">
    <div><span>Version</span><strong>${escapeHtml(version)}</strong></div>
    <div><span>Published</span><strong>${escapeHtml(published)}</strong></div>
    <div><span>Contents</span><strong>${parts}</strong></div>
  </div>
</section>`
}

function buildContents(chapters) {
    const parts = chapters.map(chapter => `
  <div class="toc-part">
    <div class="toc-part-head">
      <span class="toc-part-num">PART ${chapter.number}</span>
      <a class="toc-part-title" href="#chapter-${chapter.id}">${escapeHtml(chapter.title)}</a>
    </div>
    <p class="toc-part-blurb">${escapeHtml(chapter.blurb)}</p>
    <ul class="toc-list">
      ${chapter.sections.map(s => `<li><a href="#${s.id}">${escapeHtml(s.title)}</a></li>`).join('\n      ')}
    </ul>
  </div>`).join('')
    return `<section class="contents"><h1 class="contents-title">Contents</h1>${parts}</section>`
}

/**
 * Page footers are CSS margin boxes rather than Chrome's footer template,
 * because only CSS can leave the cover page without one.
 */
function footerCss(version, mark) {
    const markUrl = `data:image/svg+xml;base64,${Buffer.from(mark).toString('base64')}`
    return `@page { @bottom-left {
  content: "Algolia AEM Extensions \\00B7  Documentation v${version}";
  background-image: url("${markUrl}");
} }`
}

// Runs in the page. Resolves once diagrams, fonts and images are all ready to print.
const READY_SCRIPT = `
window.__pdfReady = (async () => {
  const errors = []
  const diagrams = [...document.querySelectorAll('.mermaid')]
  if (diagrams.length && !window.mermaid) {
    errors.push('Mermaid did not load; diagrams are shown as source text.')
  } else if (diagrams.length) {
    mermaid.initialize({
      startOnLoad: false,
      theme: 'base',
      securityLevel: 'loose',
      flowchart: { useMaxWidth: true, htmlLabels: true },
      themeVariables: {
        fontFamily: 'Inter, Helvetica, Arial, sans-serif',
        fontSize: '13px',
        primaryColor: '#eef1ff',
        primaryBorderColor: '#5468ff',
        primaryTextColor: '#21243d',
        secondaryColor: '#f5f5fa',
        tertiaryColor: '#ffffff',
        lineColor: '#5a5e9a',
        clusterBkg: '#f5f5fa',
        clusterBorder: '#d6d6e7',
        actorBkg: '#eef1ff',
        actorBorder: '#5468ff',
        noteBkgColor: '#fff8e1',
        noteBorderColor: '#e0c060'
      }
    })
    for (const node of diagrams) {
      try { await mermaid.run({ nodes: [node] }) }
      catch (e) { errors.push('Diagram failed to render: ' + (e && e.message ? e.message : e)) }
    }
  }
  await document.fonts.ready
  await Promise.all([...document.images].map(img => img.complete ? null
    : new Promise(resolve => { img.onload = img.onerror = resolve })))
  const brokenImages = [...document.images].filter(img => !img.naturalWidth).map(img => img.src)
  return { errors, brokenImages, diagrams: document.querySelectorAll('.mermaid svg').length }
})()`

function buildDocument({ chapters, version, published, logos }) {
    const css = fs.readFileSync(path.join(__dirname, 'theme.css'), 'utf8')
    return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Algolia AEM Extensions — Documentation v${escapeHtml(version)}</title>
<link rel="stylesheet" href="${FONTS_URL}">
<style>${css}
${footerCss(version, logos.mark)}</style>
</head>
<body>
${buildCover({ version, published, whiteLogo: logos.white })}
${buildContents(chapters)}
${chapters.map(c => c.html).join('\n')}
<script src="${MERMAID_URL}"></script>
<script>${READY_SCRIPT}</script>
</body>
</html>`
}

function findBrokenAnchors(html) {
    const ids = new Set([...html.matchAll(/ id="([^"]+)"/g)].map(m => m[1]))
    return [...new Set([...html.matchAll(/href="#([^"]+)"/g)].map(m => m[1]))].filter(id => !ids.has(id))
}

// ── Headless Chrome over the DevTools protocol ──────────────────────────────

function findChrome() {
    const found = CHROME_CANDIDATES.find(candidate => candidate && fs.existsSync(candidate))
    if (!found) throw new Error('Google Chrome not found. Set CHROME_PATH to a Chrome or Chromium binary.')
    return found
}

function withTimeout(promise, ms, label) {
    let timer
    const timeout = new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} timed out after ${ms / 1000} s`)), ms)
    })
    return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

function launchChrome(chromePath) {
    const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'docs-pdf-chrome-'))
    const proc = spawn(chromePath, [
        '--headless=new',
        '--disable-gpu',
        '--no-first-run',
        '--no-default-browser-check',
        '--hide-scrollbars',
        `--user-data-dir=${userDataDir}`,
        '--remote-debugging-port=0',
        'about:blank'
    ], { stdio: ['ignore', 'ignore', 'pipe'] })

    const endpoint = new Promise((resolve, reject) => {
        let stderr = ''
        proc.stderr.on('data', chunk => {
            stderr += chunk
            const match = stderr.match(/DevTools listening on (ws:\/\/\S+)/)
            if (match) resolve(match[1])
        })
        proc.on('exit', code => reject(new Error(`Chrome exited before it was ready (code ${code})`)))
    })

    const exited = new Promise(resolve => proc.once('exit', resolve))
    const stop = async () => {
        if (proc.exitCode === null) proc.kill()
        await withTimeout(exited, 10000, 'Stopping Chrome').catch(() => proc.kill('SIGKILL'))
        fs.rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 })
    }
    return { endpoint: withTimeout(endpoint, 30000, 'Starting Chrome'), stop }
}

async function openPageTarget(browserWsUrl) {
    const { host } = new URL(browserWsUrl)
    const targets = await (await fetch(`http://${host}/json/list`)).json()
    const page = targets.find(t => t.type === 'page')
    if (!page) throw new Error('Chrome did not open a page target')
    return page.webSocketDebuggerUrl
}

function connectCdp(wsUrl) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(wsUrl)
        const pending = new Map()
        const waiters = []
        let nextId = 0

        const settle = message => {
            const call = pending.get(message.id)
            pending.delete(message.id)
            if (message.error) call.reject(new Error(`${call.method}: ${message.error.message}`))
            else call.resolve(message.result)
        }
        const notify = message => {
            const index = waiters.findIndex(w => w.method === message.method)
            if (index !== -1) waiters.splice(index, 1)[0].resolve(message.params)
        }

        ws.onmessage = ({ data }) => {
            const message = JSON.parse(data)
            if (message.id !== undefined) settle(message)
            else notify(message)
        }
        ws.onerror = () => reject(new Error(`Could not connect to Chrome at ${wsUrl}`))
        ws.onopen = () => resolve({
            send(method, params = {}) {
                const id = ++nextId
                ws.send(JSON.stringify({ id, method, params }))
                return new Promise((res, rej) => pending.set(id, { resolve: res, reject: rej, method }))
            },
            once(method) {
                return new Promise(res => waiters.push({ method, resolve: res }))
            },
            close() { ws.close() }
        })
    })
}

async function printToPdf(htmlPath) {
    const chrome = launchChrome(findChrome())
    try {
        const cdp = await connectCdp(await openPageTarget(await chrome.endpoint))
        await cdp.send('Page.enable')
        const loaded = cdp.once('Page.loadEventFired')
        await cdp.send('Page.navigate', { url: pathToFileURL(htmlPath).href })
        await withTimeout(loaded, RENDER_TIMEOUT_MS, 'Loading the document')

        const ready = await withTimeout(cdp.send('Runtime.evaluate', {
            expression: 'window.__pdfReady',
            awaitPromise: true,
            returnByValue: true
        }), RENDER_TIMEOUT_MS, 'Rendering diagrams')

        const { data } = await withTimeout(cdp.send('Page.printToPDF', {
            printBackground: true,
            preferCSSPageSize: true,
            generateDocumentOutline: true,
            generateTaggedPDF: true
        }), RENDER_TIMEOUT_MS, 'Printing the PDF')

        cdp.close()
        return { pdf: Buffer.from(data, 'base64'), report: ready.result.value }
    } finally {
        await chrome.stop()
    }
}

// ── Main ────────────────────────────────────────────────────────────────────

async function main() {
    const args = parseArgs(process.argv.slice(2))
    const { version } = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'))
    const out = args.out || path.join(DOCS_DIR, 'pdf', `Algolia-AEM-Extensions-Documentation-v${version}.pdf`)
    const published = new Date().toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' })

    const chapterByPath = new Map(CHAPTERS.map(c => [path.join(DOCS_DIR, c.file), c]))
    const chapters = CHAPTERS.map((chapter, index) => buildChapter(chapter, index, chapterByPath))
    const logos = readLogos()
    const html = buildDocument({ chapters, version, published, logos })

    const brokenAnchors = findBrokenAnchors(html)
    brokenAnchors.forEach(id => console.warn(`warning: link to missing anchor #${id}`))

    const htmlPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'docs-pdf-')), 'documentation.html')
    fs.writeFileSync(htmlPath, html)

    const { pdf, report } = await printToPdf(htmlPath)
    report.errors.forEach(e => console.warn(`warning: ${e}`))
    report.brokenImages.forEach(src => console.warn(`warning: image failed to load: ${src}`))

    fs.mkdirSync(path.dirname(out), { recursive: true })
    fs.writeFileSync(out, pdf)

    if (args.keepHtml) console.log(`HTML kept at ${htmlPath}`)
    else fs.rmSync(path.dirname(htmlPath), { recursive: true, force: true })

    const problems = brokenAnchors.length + report.errors.length + report.brokenImages.length
    console.log(`Wrote ${path.relative(process.cwd(), out)} (${(pdf.length / 1024 / 1024).toFixed(1)} MB, `
        + `${chapters.length} chapters, ${report.diagrams} diagrams, ${problems} warning${problems === 1 ? '' : 's'})`)
}

main().catch(error => {
    console.error(error.message)
    process.exit(1)
})
