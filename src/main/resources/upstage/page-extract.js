() => {
  // 상품 페이지의 DOM → markdown 비슷한 글 + 본문 이미지 목록. UrlSnapshotter 가 page.evaluate 로 돌린다.
  // 글자는 HTML 에 있는 그대로 가져온다(OCR 오류 없음, 비용 0). 이미지 자리는 [[IMG n]] 으로 남기고, 이미지는 따로 OCR 한다.
  // 숨겨진 요소(탭·접힌 영역)도 읽는다 — 탭 뒤의 요금표·일정이 빠지지 않게. 무관한 덩어리는 뒤 단계의 블록 라벨이 거른다.
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'SVG', 'CANVAS', 'IFRAME', 'HEADER', 'FOOTER', 'NAV', 'ASIDE', 'FORM', 'BUTTON', 'SELECT', 'INPUT', 'TEXTAREA']);
  const SKIP_ROLES = new Set(['banner', 'navigation', 'contentinfo', 'search', 'dialog']);
  const BLOCK = new Set(['P', 'DIV', 'SECTION', 'ARTICLE', 'MAIN', 'UL', 'OL', 'LI', 'DL', 'DT', 'DD', 'BLOCKQUOTE', 'PRE', 'FIGURE', 'FIGCAPTION', 'HR', 'ADDRESS', 'DETAILS', 'SUMMARY', 'CENTER']);
  const MAX_IMAGES = 15;
  const out = [];
  const images = [];
  const seen = new Set();
  let line = '';

  const flush = () => {
    const s = line.replace(/[ \t ]+/g, ' ').trim();
    if (s) out.push(s);
    line = '';
  };
  const skip = (el) => SKIP.has(el.tagName) || SKIP_ROLES.has(el.getAttribute('role') || '') || el.getAttribute('aria-hidden') === 'true';

  // srcset 에서 가장 큰 후보 (WordPress 등은 화면용 축소본을 src 로, 원본을 srcset 에 둔다)
  const bestSrc = (img) => {
    let best = img.currentSrc || img.src || img.getAttribute('data-src') || '';
    let bestW = 0;
    const set = img.getAttribute('srcset') || img.getAttribute('data-srcset') || '';
    for (const part of set.split(',')) {
      const [u, d] = part.trim().split(/\s+/);
      const w = parseInt(d || '', 10);
      if (u && w > bestW) { bestW = w; best = new URL(u, document.baseURI).href; }
    }
    return best;
  };

  // 본문 이미지만: 아이콘·장식·로고는 뺀다(크기 기준). 같은 이미지는 한 번만
  const image = (img) => {
    const src = bestSrc(img);
    if (!src || src.startsWith('data:') || /\.svg(\?|$)/i.test(src) || seen.has(src)) return;
    const w = Math.max(img.naturalWidth || 0, img.width || 0);
    const h = Math.max(img.naturalHeight || 0, img.height || 0);
    if (w < 300 || h < 60) return;
    if (images.length >= MAX_IMAGES) return;
    seen.add(src);
    images.push({ n: images.length + 1, url: src, width: w, height: h });
    flush();
    out.push(`[[IMG ${images.length}]]`);
  };

  // 표 → 병합 칸을 펼친 격자 → 파이프 표
  const table = (t) => {
    const grid = [];
    [...t.rows].forEach((tr, r) => {
      grid[r] = grid[r] || [];
      let c = 0;
      for (const cell of tr.cells) {
        while (grid[r][c] !== undefined) c++;
        const text = cell.innerText.replace(/\s+/g, ' ').replace(/\|/g, '/').trim();
        const rs = Math.min(parseInt(cell.rowSpan, 10) || 1, 50);
        const cs = Math.min(parseInt(cell.colSpan, 10) || 1, 50);
        for (let dr = 0; dr < rs; dr++) {
          grid[r + dr] = grid[r + dr] || [];
          for (let dc = 0; dc < cs; dc++) grid[r + dr][c + dc] = text;
        }
        c += cs;
      }
    });
    const width = Math.max(0, ...grid.map((row) => row.length));
    if (!width) return;
    flush();
    grid.forEach((row, i) => {
      const cells = Array.from({ length: width }, (_, k) => (row[k] === undefined ? '' : row[k]));
      out.push('| ' + cells.join(' | ') + ' |');
      if (i === 0) out.push('|' + ' --- |'.repeat(width));
    });
    out.push('');
    t.querySelectorAll('img').forEach(image);
  };

  const walk = (node) => {
    if (node.nodeType === Node.TEXT_NODE) { line += node.textContent; return; }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const el = node;
    if (skip(el)) return;
    const tag = el.tagName;
    if (tag === 'TABLE') { table(el); return; }
    if (tag === 'IMG') { image(el); return; }
    if (tag === 'BR') { flush(); return; }
    const heading = /^H([1-6])$/.exec(tag);
    if (heading) {
      flush();
      const text = el.innerText.replace(/\s+/g, ' ').trim();
      if (text) out.push('#'.repeat(+heading[1]) + ' ' + text);
      el.querySelectorAll('img').forEach(image);
      return;
    }
    const block = BLOCK.has(tag);
    if (block) flush();
    if (tag === 'LI') line += '- ';
    for (const child of el.childNodes) walk(child);
    if (block) flush();
  };

  const root = document.body;
  walk(root);
  flush();
  const markdown = out.join('\n').replace(/\n{3,}/g, '\n\n');
  const textLength = markdown.replace(/\[\[IMG \d+]]/g, '').replace(/\s+/g, '').length;
  return { markdown, images, textLength, title: document.title || '' };
}
