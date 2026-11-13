/* VOD per Mac — interfaccia (JavaScript senza librerie). Tutti i dati passano dal server locale /api. */
'use strict';

const KEY = window.VOD_KEY;
const app = document.getElementById('app');
const modalRoot = document.getElementById('modal-root');
const COLORS = ['#F2B13B', '#5FB0FF', '#5BD6A2', '#FF7A6B', '#B08CFF', '#FF9ED2', '#7FD6E8'];

const S = {
  state: null,          // profili, profilo attivo, versione
  browse: {},           // per tipo: { q, results, info, loading, error, searched }
  session: null,        // riproduzione in corso
  update: null,         // stato aggiornamento
  settingsEditing: null,
  detailSeason: {},     // slug -> stagione selezionata
};

// ---------- utilità ----------

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const color = (i) => COLORS[((i % COLORS.length) + COLORS.length) % COLORS.length];
const initial = (name) => esc((name || '?').trim().charAt(0).toUpperCase() || '?');

async function api(route, body = {}) {
  const r = await fetch('/api/' + route, {
    method: 'POST',
    headers: { 'X-VOD-Key': KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  let j;
  try { j = await r.json(); } catch { throw new Error('Risposta non valida dal programma.'); }
  if (!r.ok || j.error) throw new Error(j.error || 'Errore');
  return j;
}

function fmt(ms) {
  const s = Math.max(Math.floor((ms || 0) / 1000), 0);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`;
}
const remaining = (p) => `${Math.max(1, Math.round((p.durationMs - p.positionMs) / 60000))} min rimasti`;
const runtime = (min) => (min >= 60 ? `${Math.floor(min / 60)} h ${min % 60} min` : `${min} min`);

let toastTimer;
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 4500);
}

const ICON = {
  home: '<path d="M4 11l8-7 8 7v9h-5v-6H9v6H4z"/>',
  film: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M10 9.5v5l4.5-2.5z"/>',
  series: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M8 3l4 3 4-3"/>',
  heart: '<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/>',
  motors: '<circle cx="12" cy="13" r="8"/><path d="M12 13l4-4"/><path d="M9 3h6"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M2 12h3M19 12h3M4.9 19.1L7 17M17 7l2.1-2.1"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
  back: '<path d="M15 18l-6-6 6-6"/>',
  play: '<path d="M7 4.5v15l12.5-7.5z" fill="currentColor" stroke="none"/>',
  pause: '<rect x="6" y="4.5" width="4" height="15" rx="1" fill="currentColor" stroke="none"/><rect x="14" y="4.5" width="4" height="15" rx="1" fill="currentColor" stroke="none"/>',
  rew: '<path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 3v5h5"/>',
  fwd: '<path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 3v5h-5"/>',
  next: '<path d="M5 5l10 7-10 7z"/><path d="M19 5v14"/>',
  vol: '<path d="M4 9h4l5-4v14l-5-4H4z"/><path d="M16.5 8.5a5 5 0 0 1 0 7"/>',
  mute: '<path d="M4 9h4l5-4v14l-5-4H4z"/><path d="M17 9l5 6M22 9l-5 6"/>',
  full: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
};
const icon = (name, size = 20) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[name]}</svg>`;

function activeProfile() {
  return S.state?.profiles.find((p) => p.id === S.state.activeId) || S.state?.profiles[0];
}
const isKid = () => !!activeProfile()?.isKid;

function avatar(p, size) {
  return `<span class="avatar" style="width:${size}px;height:${size}px;font-size:${Math.round(size * 0.42)}px;background:${color(p.color)}">${initial(p.name)}</span>`;
}

function art(image, title) {
  return image
    ? `<span class="initials" aria-hidden="true">${initial(title)}</span><img src="${esc(image)}" alt="" loading="lazy" onerror="this.remove()">`
    : `<span class="initials" aria-hidden="true">${initial(title)}${esc((title || '').trim().charAt(1) || '')}</span>`;
}

// ---------- struttura: barra in alto e dock ----------

const TABS = [
  ['home', 'Home', '#/home', 'home'],
  ['movie', 'Film', '#/browse/movie', 'film'],
  ['series', 'Serie TV', '#/browse/series', 'series'],
  ['favorites', 'Preferiti', '#/favorites', 'heart'],
  ['motors', 'Motori', '#/motors', 'motors'],
  ['settings', 'Impostazioni', '#/settings', 'gear'],
];

function shell(active, body, opts = {}) {
  const p = activeProfile();
  const tabs = TABS.filter((t) => !(t[0] === 'settings' && isKid()));
  const updateDot = S.update?.phase === 'available' || S.update?.phase === 'ready';
  const placeholder = opts.searchKind === 'series' ? 'Cerca serie TV' : opts.searchKind === 'movie' ? 'Cerca film' : 'Cerca film e serie';
  return `
  <div class="shell">
    <header class="topbar">
      <div class="logo">VOD<span>.</span></div>
      <label class="search">${icon('search', 16)}
        <input id="search" type="search" placeholder="${placeholder}" aria-label="${placeholder}" value="${esc(opts.query || '')}" autocomplete="off" spellcheck="false">
        <span class="kbd">⌘F</span>
      </label>
      ${p ? `<button class="profile-chip" data-act="profiles" aria-label="Cambia profilo">${avatar(p, 32)}${esc(p.name)}</button>` : ''}
    </header>
    <main class="content" id="content">${body}</main>
    <nav class="dock" aria-label="Sezioni">
      ${tabs.map((t) => `<a href="${t[2]}" ${t[0] === active ? 'aria-current="page"' : ''}>${icon(t[3])}${t[1]}${t[0] === 'settings' && updateDot ? '<span class="dot" aria-label="Aggiornamento disponibile"></span>' : ''}</a>`).join('')}
    </nav>
  </div>`;
}

function bindShell(searchKind) {
  const input = document.getElementById('search');
  if (!input) return;
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      const kind = searchKind || 'all';
      S.browse[kind] = { ...(S.browse[kind] || {}), q: input.value.trim() };
      if (location.hash === `#/browse/${kind}`) runSearch(kind); else location.hash = `#/browse/${kind}`;
    }
  });
  if (searchKind) {
    let t;
    input.addEventListener('input', () => {
      clearTimeout(t);
      t = setTimeout(() => {
        const q = input.value.trim();
        if (q.length >= 2 && q !== S.browse[searchKind]?.q) {
          S.browse[searchKind] = { ...(S.browse[searchKind] || {}), q };
          runSearch(searchKind);
        }
      }, 600);
    });
  }
}

const loading = () => '<div class="empty"><div class="spinner" aria-label="Caricamento"></div></div>';
const empty = (msg, action = '') => `<div class="empty"><p>${esc(msg)}</p>${action}</div>`;

// ---------- router ----------

let leaveHook = null;

async function render() {
  if (leaveHook) { const h = leaveHook; leaveHook = null; h(); }
  closeModal();
  const parts = location.hash.replace(/^#\/?/, '').split('/');
  const [view, arg] = [parts[0] || 'home', decodeURIComponent(parts.slice(1).join('/'))];
  try {
    switch (view) {
      case 'profiles': return renderProfiles();
      case 'home': return await renderHome();
      case 'browse': return renderBrowse(arg || 'all');
      case 'favorites': return await renderFavorites();
      case 'detail': return await renderDetail(arg);
      case 'player': return renderPlayer();
      case 'motors': return await renderMotors(arg || 'motogp');
      case 'settings': return await renderSettings();
      default: location.hash = '#/home';
    }
  } catch (e) {
    app.innerHTML = shell('', empty(e.message, '<button class="btn" data-act="reload">Riprova</button>'));
    bindShell();
  }
}

async function refreshState() {
  S.state = await api('state');
}

// ---------- Chi guarda? ----------

function renderProfiles() {
  const profiles = S.state.profiles;
  app.innerHTML = `
  <section class="who">
    <h1>Chi guarda?</h1>
    <div class="tiles">
      ${profiles.map((p) => `
        <button class="tile" data-act="switch" data-id="${esc(p.id)}">
          ${avatar(p, 140)}${esc(p.name)}${p.isKid ? '<span class="kid">Bambini</span>' : ''}
        </button>`).join('')}
    </div>
    <p class="hint">Da un profilo bambini a uno standard serve il PIN genitori</p>
  </section>`;
  const cur = app.querySelector(`.tile[data-id="${CSS.escape(S.state.activeId)}"]`);
  (cur || app.querySelector('.tile'))?.focus();
}

async function switchProfile(id, pin) {
  const r = await api('switch', { id, pin });
  if (r.pinRequired) {
    const target = S.state.profiles.find((p) => p.id === id);
    pinPad({
      title: 'PIN genitori',
      subtitle: `Per passare a «${target?.name}» inserisci il PIN`,
      onComplete: async (value, setError) => {
        try { await switchProfile(id, value); } catch (e) { setError(e.message); }
      },
    });
    return;
  }
  closeModal();
  S.browse = {};
  await refreshState();
  location.hash = '#/home';
  if (location.hash === '#/home') render();
}

// ---------- Home ----------

async function renderHome() {
  app.innerHTML = shell('home', loading());
  bindShell();
  const h = await api('home');
  const cw = h.continue;
  let body;
  if (!cw.length && !h.favorites.length) {
    body = empty('Qui troverai i film e gli episodi che stai guardando, pronti da riprendere.', '<a class="btn primary" href="#/browse/all">Cerca un titolo</a>');
  } else {
    const top = cw[0];
    body = (top ? `
      <section class="hero" aria-labelledby="hero-t">
        ${top.image ? `<div class="bg" style="background-image:url('${esc(top.image)}')"></div>` : ''}
        <div class="info">
          <p class="kicker">Riprendi da dove eri</p>
          <h1 id="hero-t">${esc(top.title)}</h1>
          <p class="hint" style="font-size:15px;color:var(--text-soft)">${esc(cwMeta(top))}</p>
          ${!top.watched && top.durationMs ? `<span class="bar"><span style="width:${Math.round((top.positionMs / top.durationMs) * 100)}%;background:var(--accent)"></span></span>` : ''}
          <div class="actions">
            <button class="btn primary" data-act="cw" data-i="0">${icon('play', 14)} ${top.watched ? 'Prossimo episodio' : 'Riprendi'}</button>
            ${top.origin === 'vod' ? `<a class="btn" href="#/detail/${encodeURIComponent(top.ownerId)}">Dettagli</a>` : ''}
          </div>
        </div>
      </section>` : '') +
      (cw.length ? `
      <div class="section-title"><h2>Continua a guardare</h2><span class="hint">Il progresso è salvato in automatico</span></div>
      <div class="row">${cw.map((p, i) => posterHtml({ title: p.title, image: p.image, meta: cwMeta(p), progress: p.watched ? null : p.positionMs / (p.durationMs || 1), act: 'cw', i })).join('')}</div>` : '') +
      (h.favorites.length ? `
      <div class="section-title"><h2>I tuoi preferiti</h2><a class="hint" href="#/favorites">Vedi tutti</a></div>
      <div class="row">${h.favorites.map((f) => posterHtml({ title: f.title, image: f.image, href: `#/detail/${encodeURIComponent(f.slug)}` })).join('')}</div>` : '');
  }
  document.getElementById('content').innerHTML = body;
  S.homeItems = cw;
}

function cwMeta(p) {
  if (p.watched && p.type === 'series') return 'Prossimo episodio';
  const left = p.durationMs ? remaining(p) : '';
  if (p.season != null) return `S${p.season} · E${p.episode}${left ? ' · ' + left : ''}`;
  return [p.origin === 'rsi' ? 'RSI' : p.type === 'movie' ? 'Film' : '', left].filter(Boolean).join(' · ');
}

async function openContinue(i) {
  const p = S.homeItems?.[i];
  if (!p) return;
  if (p.origin === 'rsi') {
    const id = p.ownerId.split(':').pop();
    try {
      startSession(await api('rsi/play', { id, urn: p.ownerId.replace(/^rsi:/, ''), title: p.title, show: p.subtitle, image: p.image }));
    } catch (e) { toast(e.message); }
    return;
  }
  location.hash = `#/detail/${encodeURIComponent(p.ownerId)}`;
}

function posterHtml({ title, image, meta, metaOk, progress, href, act, i, slug, hover }) {
  const tag = href ? 'a' : 'button';
  const attrs = href ? `href="${href}"` : `data-act="${act}" data-i="${i ?? ''}"`;
  return `
  <${tag} class="poster" ${attrs} ${slug ? `data-slug="${esc(slug)}"` : ''}>
    <span class="art">${art(image, title)}
      ${progress ? `<span class="bar"><span style="width:${Math.min(100, Math.round(progress * 100))}%"></span></span>` : ''}
      ${hover || ''}
    </span>
    <span class="name">${esc(title)}</span>
    ${meta ? `<span class="meta ${metaOk ? 'ok' : ''}">${esc(meta)}</span>` : ''}
  </${tag}>`;
}

// ---------- Film / Serie TV / ricerca ----------

function renderBrowse(kind) {
  const b = (S.browse[kind] = S.browse[kind] || { q: '', results: [], info: {} });
  const title = kind === 'movie' ? 'Film' : kind === 'series' ? 'Serie TV' : 'Ricerca';
  app.innerHTML = shell(kind === 'all' ? '' : kind, `<div id="browse"></div>`, { searchKind: kind, query: b.q === '*' ? '' : b.q });
  bindShell(kind);
  paintBrowse(kind, title);
  const input = document.getElementById('search');
  if (!b.searched) input.focus();
  if (b.q && !b.searched) runSearch(kind);
  else if (!b.q && !b.searched && !b.triedAll) {
    // Elenco completo, se il server lo supporta (la libreria del Mac sì): la ricerca vuota resta disponibile
    b.triedAll = true;
    b.q = '*';
    runSearch(kind, true);
  }
}

function visible(kind) {
  const b = S.browse[kind];
  return b.results
    .map((r) => ({ ...r, ...(b.info[r.slug] || {}) }))
    .map((r) => ({ ...r, type: r.type && r.type !== 'unknown' ? r.type : 'unknown' }))
    .filter((r) => kind === 'all' || r.type === kind || r.type === 'unknown')
    .filter((r) => !b.kid || r.kidSafe === true);
}

function paintBrowse(kind, title) {
  const el = document.getElementById('browse');
  if (!el) return;
  const b = S.browse[kind];
  title = title || (kind === 'movie' ? 'Film' : kind === 'series' ? 'Serie TV' : 'Ricerca');
  const items = b.searched ? visible(kind) : [];
  const pending = b.pending || 0;
  let status = '';
  if (b.loading) status = 'Cerco…';
  else if (pending) status = b.kid ? `Controllo i titoli adatti ai bambini… (${pending})` : `Riconosco film e serie… (${pending})`;
  else if (b.searched) status = b.showingAll ? `${items.length} titoli` : `${items.length} risultati per «${b.q}»`;

  let body;
  if (b.kidNeedsTmdb) body = empty('Il profilo bambini mostra solo titoli verificati con TMDB. Chiedi a un adulto di inserire la chiave TMDB per questo profilo nelle Impostazioni.');
  else if (b.error) body = empty(b.error, '<button class="btn" data-act="research">Riprova</button>');
  else if (b.loading && !b.results.length) body = loading();
  else if (!b.searched) body = empty(kind === 'series' ? 'Cerca una serie TV: scrivi nel campo in alto e premi Invio.' : kind === 'movie' ? 'Cerca un film: scrivi nel campo in alto e premi Invio.' : 'Scrivi nel campo in alto e premi Invio.');
  else if (!items.length && !pending) body = empty(b.kid ? 'Nessun titolo adatto ai bambini per questa ricerca.' : 'Nessun risultato.');
  else {
    body = `<div class="grid">${items.map((r) => posterHtml({
      title: r.name, image: r.image, href: `#/detail/${encodeURIComponent(r.slug)}`, slug: r.slug,
      meta: kind === 'all' ? (r.type === 'movie' ? 'Film' : r.type === 'series' ? 'Serie TV' : '…') : (r.type === 'unknown' ? '…' : ''),
      hover: `<span class="hover-actions"><span class="round play">${icon('play', 14)}</span></span>`,
    })).join('')}</div>`;
  }
  el.innerHTML = `
    <div class="section-title" style="margin-top:8px"><h2 style="font-size:28px">${title}</h2><span class="hint">${esc(status)}</span></div>
    ${body}`;
}

async function runSearch(kind, quietAll) {
  const b = S.browse[kind];
  if (!b.q) return;
  b.showingAll = !!quietAll;
  const seq = (b.seq = (b.seq || 0) + 1);
  Object.assign(b, { loading: true, error: null, info: {}, pending: 0 });
  paintBrowse(kind);
  try {
    const r = await api('search', { q: b.q });
    if (seq !== b.seq) return;
    if (quietAll && !r.results.length) { Object.assign(b, { loading: false, q: '', showingAll: false }); paintBrowse(kind); return; }
    Object.assign(b, { loading: false, searched: true, results: r.results, kid: r.kid, kidNeedsTmdb: r.kidNeedsTmdb });
    for (const x of r.results) if (x.kidSafe !== null && x.kidSafe !== undefined) b.info[x.slug] = { kidSafe: x.kidSafe };
    paintBrowse(kind);
    const need = r.results.filter((x) => x.type === 'unknown' || (r.kid && x.kidSafe == null)).map((x) => x.slug);
    // Classificazione in blocchi da 4: i risultati compaiono man mano
    for (let i = 0; i < need.length && !r.kidNeedsTmdb; i += 4) {
      if (seq !== b.seq) return;
      b.pending = need.length - i;
      paintBrowse(kind);
      const info = await api('inspect', { slugs: need.slice(i, i + 4) });
      if (seq !== b.seq) return;
      Object.assign(b.info, info);
    }
    b.pending = 0;
    paintBrowse(kind);
  } catch (e) {
    if (seq !== b.seq) return;
    if (quietAll) { Object.assign(b, { loading: false, q: '', showingAll: false }); paintBrowse(kind); return; }
    Object.assign(b, { loading: false, searched: true, error: e.message, pending: 0 });
    paintBrowse(kind);
  }
}

// ---------- Preferiti ----------

async function renderFavorites() {
  app.innerHTML = shell('favorites', loading());
  bindShell();
  const { items } = await api('favorites');
  const series = items.filter((f) => f.type === 'series');
  const movies = items.filter((f) => f.type !== 'series');
  const row = (label, list) => list.length ? `
    <div class="section-title"><h2>${label} <span class="hint" style="font-size:16px">· ${list.length}</span></h2></div>
    <div class="grid">${list.map((f) => posterHtml({ title: f.title, image: f.image, meta: f.status || '', href: `#/detail/${encodeURIComponent(f.slug)}` })).join('')}</div>` : '';
  document.getElementById('content').innerHTML = items.length
    ? row('Serie TV', series) + row('Film', movies)
    : empty('Nessun preferito. Apri un titolo e premi «Aggiungi ai preferiti».');
}

// ---------- Dettaglio ----------

async function renderDetail(slug) {
  app.innerHTML = `<div class="detail">${loading()}</div>`;
  const v = await api('detail', { slug });
  S.detail = v;
  paintDetail();
}

function paintDetail() {
  const { detail: d, resume, progress, isFavorite, kidBlocked } = S.detail;
  const isSeries = d.type === 'series';
  const back = `<button class="btn ghost back" data-act="back">${icon('back', 16)} Indietro</button> <span class="kbd">Esc</span>`;
  if (kidBlocked) {
    app.innerHTML = `<div class="detail"><div class="wrap">${back}${empty(`«${d.title}» non è disponibile nel profilo bambini.`)}</div></div>`;
    return;
  }
  const kicker = [isSeries ? 'Serie TV' : 'Film', d.year, isSeries ? `${d.seasons.length} stagioni` : d.runtime ? runtime(d.runtime) : null,
    d.rating ? `★ ${Number(d.rating).toFixed(1).replace('.', ',')}` : null].filter(Boolean).join(' · ');
  const resumeIdx = d.seasons.findIndex((s) => s.number === resume?.season);
  const sel = Math.min(S.detailSeason[d.slug] ?? Math.max(resumeIdx, 0), Math.max(d.seasons.length - 1, 0));
  const season = d.seasons[sel];
  const movieP = progress[`vod:${d.slug}`];

  app.innerHTML = `
  <div class="detail">
    ${d.backdrop ? `<div class="backdrop" style="background-image:url('${esc(d.backdrop)}')"></div>` : ''}
    <div class="wrap">
      ${back}
      <div class="layout">
        <div class="cover">${d.image ? `<img src="${esc(d.image)}" alt="">` : ''}</div>
        <div class="main">
          <p class="kicker">${esc(kicker)}</p>
          <h1>${esc(d.title)}</h1>
          ${d.genres?.length ? `<p class="hint" style="font-size:15px;color:var(--text-soft);margin:0">${esc(d.genres.slice(0, 4).join(' · '))}</p>` : ''}
          ${d.plot ? `<p class="plot">${esc(d.plot)}</p>` : ''}
          <div class="actions">
            ${resume ? `<button class="btn primary" data-act="play-resume">${icon('play', 14)} ${esc(resume.label)}</button>` : ''}
            ${resume && resume.positionMs > 0 ? '<button class="btn ghost" data-act="play-start">Dall\'inizio</button>' : ''}
            <button class="btn ghost" data-act="fav" aria-pressed="${isFavorite}">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="${isFavorite ? 'var(--accent)' : 'none'}" stroke="${isFavorite ? 'var(--accent)' : 'currentColor'}" stroke-width="2" aria-hidden="true">${ICON.heart}</svg>
              ${isFavorite ? 'Nei preferiti' : 'Aggiungi ai preferiti'}
            </button>
            ${!isSeries ? `<button class="btn ghost" data-act="watched-movie">${movieP?.watched ? 'Segna come non visto' : 'Segna come visto'}</button>` : ''}
          </div>
          ${!isSeries && movieP?.watched ? '<p class="hint" style="color:var(--watched);margin:0">✓ Visto</p>' : ''}
          ${isSeries && d.seasons.length ? `
            <div class="tabs" role="tablist">
              ${d.seasons.map((s, i) => {
                const seen = s.episodes.filter((e) => progress[`vod:${d.slug}:s${s.number}:e${e.number}`]?.watched).length;
                return `<button role="tab" aria-selected="${i === sel}" data-act="season" data-i="${i}">${esc(s.name)}${i === sel && seen ? ` · ${seen}/${s.episodes.length} visti` : ''}</button>`;
              }).join('')}
            </div>
            <ul class="episodes">
              ${season.episodes.map((e) => {
                const p = progress[`vod:${d.slug}:s${season.number}:e${e.number}`];
                const seen = p?.watched;
                const partial = !seen && p && p.positionMs > 0 && p.durationMs > 0;
                const st = seen ? '✓ Visto' : partial ? remaining(p) : e.runtime ? `${e.runtime} min` : '';
                return `
                <li class="episode ${seen ? 'seen' : ''}" data-act="play-ep" data-s="${season.number}" data-e="${e.number}" tabindex="0">
                  <span class="still">${e.image ? `<img src="${esc(e.image)}" alt="" loading="lazy">` : ''}
                    ${partial ? `<span class="bar"><span style="width:${Math.round((p.positionMs / p.durationMs) * 100)}%"></span></span>` : ''}</span>
                  <span class="txt"><span class="t">${e.number}. ${esc(e.name)}</span>${e.plot ? `<span class="p">${esc(e.plot)}</span>` : ''}</span>
                  <button class="btn ghost toggle" data-act="toggle-ep" data-s="${season.number}" data-e="${e.number}" data-w="${seen ? 1 : 0}">${seen ? 'Segna non visto' : 'Segna visto'}</button>
                  <span class="st ${seen ? 'ok' : ''}">${esc(st)}</span>
                </li>`;
              }).join('')}
            </ul>` : ''}
        </div>
      </div>
    </div>
  </div>`;
}

async function reloadDetail() {
  const scroll = app.querySelector('.detail')?.scrollTop || 0;
  S.detail = await api('detail', { slug: S.detail.detail.slug });
  paintDetail();
  const el = app.querySelector('.detail');
  if (el) el.scrollTop = scroll;
}

async function play(slug, season, episode, resumeMs) {
  toast('Carico il flusso…');
  try {
    const r = await api('play', { slug, season, episode, resumeMs });
    document.getElementById('toast').hidden = true;
    if (r.session) return startSession(r.session);
    chooseSource(r.links);
  } catch (e) { toast(e.message); }
}

function chooseSource(links) {
  openModal(`
    <div class="modal wide-modal" role="dialog" aria-modal="true" aria-labelledby="src-t">
      <h2 id="src-t">Scegli la sorgente</h2>
      <p>Il server ha restituito più flussi.</p>
      ${links.map((l, i) => `<button class="source" data-act="source" data-i="${i}"><span>${esc(l.label)}</span><small>…${esc(l.url.split('?')[0].slice(-56))}</small></button>`).join('')}
      <button class="btn" data-act="close-modal">Annulla</button>
    </div>`);
  S.links = links;
  modalRoot.querySelector('.source')?.focus();
}

function startSession(session) {
  S.session = session;
  if (location.hash === '#/player') render(); else location.hash = '#/player';
}

// ---------- Player ----------

function renderPlayer() {
  const s = S.session;
  if (!s) { history.back(); return; }
  app.innerHTML = `
  <div class="player ui" id="player">
    <video id="video" playsinline preload="auto"></video>
    <div class="spinner buffer" id="buffer" hidden></div>
    <div class="overlay">
      <div class="top">
        <button class="round" data-act="exit" aria-label="Indietro" style="width:38px;height:38px">${icon('back', 18)}</button>
        <div><h1>${esc(s.title)}</h1>${s.subtitle ? `<p>${esc(s.subtitle)}</p>` : ''}</div>
        ${s.isLive ? '<span class="live-badge">IN DIRETTA</span>' : ''}
      </div>
      <div class="bottom">
        ${s.isLive ? '' : `<div class="scrub"><span id="cur">0:00</span><input id="seek" type="range" min="0" max="1000" value="0" aria-label="Posizione"><span id="dur">0:00</span></div>`}
        <div class="ctrls">
          ${s.isLive ? '' : `<button class="ic" data-act="rew" aria-label="Indietro di 10 secondi">${icon('rew', 24)}</button>`}
          <button class="ic pp" data-act="pp" id="pp" aria-label="Pausa">${icon('pause', 22)}</button>
          ${s.isLive ? '' : `<button class="ic" data-act="fwd" aria-label="Avanti di 10 secondi">${icon('fwd', 24)}</button>`}
          ${s.next ? `<button class="ic" data-act="next" aria-label="Episodio successivo">${icon('next', 22)}</button>` : ''}
          <p class="hint" style="margin:0 0 0 14px">Spazio pausa · ← → 10 s · F schermo intero · M muto · Esc esci</p>
          <span style="flex:1"></span>
          <button class="ic" data-act="mute" id="mute" aria-label="Muto">${icon('vol', 22)}</button>
          <button class="ic" data-act="full" aria-label="Schermo intero">${icon('full', 20)}</button>
        </div>
      </div>
    </div>
    <div id="player-extra"></div>
  </div>`;

  const v = document.getElementById('video');
  const player = document.getElementById('player');
  let lastSave = 0, hideTimer, ended = false, countdown;

  const save = () => {
    if (s.isLive || !v.duration || !isFinite(v.duration)) return;
    api('progress', { token: s.token, positionMs: Math.round(v.currentTime * 1000), durationMs: Math.round(v.duration * 1000) }).catch(() => {});
  };
  const showUi = () => {
    player.classList.add('ui');
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (!v.paused) player.classList.remove('ui'); }, 3000);
  };
  const setPP = () => {
    const b = document.getElementById('pp');
    if (b) { b.innerHTML = icon(v.paused ? 'play' : 'pause', 22); b.setAttribute('aria-label', v.paused ? 'Riproduci' : 'Pausa'); }
  };

  v.src = s.src;
  v.addEventListener('loadedmetadata', () => {
    if (s.resumeMs > 0 && isFinite(v.duration) && s.resumeMs < v.duration * 1000 - 5000) v.currentTime = s.resumeMs / 1000;
  }, { once: true });
  v.addEventListener('waiting', () => { document.getElementById('buffer').hidden = false; });
  v.addEventListener('playing', () => { document.getElementById('buffer').hidden = true; setPP(); showUi(); });
  v.addEventListener('pause', () => { setPP(); player.classList.add('ui'); save(); });
  v.addEventListener('timeupdate', () => {
    if (!s.isLive && isFinite(v.duration)) {
      const seek = document.getElementById('seek');
      if (seek && !seek.dragging) seek.value = Math.round((v.currentTime / v.duration) * 1000);
      document.getElementById('cur').textContent = fmt(v.currentTime * 1000);
      document.getElementById('dur').textContent = fmt(v.duration * 1000);
    }
    if (Date.now() - lastSave > 10000 && !v.paused) { lastSave = Date.now(); save(); }
  });
  v.addEventListener('ended', () => {
    ended = true;
    save();
    if (!s.next) { exitPlayer(); return; }
    let left = 10;
    const paint = () => {
      document.getElementById('player-extra').innerHTML = `
        <section class="nextup" aria-labelledby="nx-t">
          <p class="kicker">Prossimo episodio tra ${left} s</p>
          <h2 id="nx-t">S${s.next.season} · E${s.next.episode} · ${esc(s.next.name)}</h2>
          <div class="actions"><button class="btn primary" data-act="next">Riproduci ora</button><button class="btn ghost" data-act="cancel-next">Annulla</button></div>
        </section>`;
    };
    paint();
    countdown = setInterval(() => { left -= 1; if (left <= 0) { clearInterval(countdown); goNext(); } else paint(); }, 1000);
    S.cancelNext = () => { clearInterval(countdown); exitPlayer(); };
  });
  v.addEventListener('error', () => {
    document.getElementById('buffer').hidden = true;
    document.getElementById('player-extra').innerHTML = `
      <div class="player-error"><p>Il flusso non si riesce a riprodurre. Il link potrebbe essere scaduto o non essere un HLS valido.</p>
      <div class="actions"><button class="btn primary" data-act="retry-video">Riprova</button><button class="btn" data-act="exit">Indietro</button></div></div>`;
  });

  const seek = document.getElementById('seek');
  if (seek) {
    seek.addEventListener('input', () => { seek.dragging = true; document.getElementById('cur').textContent = fmt((seek.value / 1000) * v.duration * 1000); });
    seek.addEventListener('change', () => { v.currentTime = (seek.value / 1000) * v.duration; seek.dragging = false; });
  }
  player.addEventListener('mousemove', showUi);

  v.play().catch(() => {
    // Riproduzione automatica bloccata: serve un clic
    document.getElementById('player-extra').innerHTML = `<button class="bigplay" data-act="pp" aria-label="Riproduci">${icon('play', 36)}</button>`;
  });
  showUi();

  S.player = {
    v,
    toggle: () => { v.paused ? v.play() : v.pause(); document.getElementById('player-extra').querySelector('.bigplay')?.remove(); showUi(); },
    seekBy: (sec) => { if (!s.isLive && isFinite(v.duration)) { v.currentTime = Math.max(0, Math.min(v.duration - 1, v.currentTime + sec)); showUi(); } },
    mute: () => { v.muted = !v.muted; document.getElementById('mute').innerHTML = icon(v.muted ? 'mute' : 'vol', 22); showUi(); },
    retry: () => { document.getElementById('player-extra').innerHTML = ''; const t = v.currentTime; v.src = s.src; v.currentTime = t; v.play().catch(() => {}); },
  };
  leaveHook = () => {
    clearInterval(countdown);
    clearTimeout(hideTimer);
    if (!ended) save();
    v.pause();
    v.removeAttribute('src');
    v.load();
    S.player = null;
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  };
}

async function goNext() {
  try {
    toast('Carico il prossimo episodio…');
    const session = await api('next', { token: S.session.token });
    document.getElementById('toast').hidden = true;
    startSession(session);
  } catch (e) { toast(e.message); exitPlayer(); }
}

function exitPlayer() {
  if (history.length > 1) history.back(); else location.hash = '#/home';
}

function toggleFullscreen() {
  if (window.pywebview?.api?.toggle_fullscreen) window.pywebview.api.toggle_fullscreen();
  else if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen?.();
}

// ---------- Motori ----------

async function renderMotors(sport) {
  const tabs = `<div class="segmented" role="group" aria-label="Sport">
      <button data-act="sport" data-v="motogp" aria-pressed="${sport === 'motogp'}">MotoGP</button>
      <button data-act="sport" data-v="f1" aria-pressed="${sport === 'f1'}">Formula 1</button></div>`;
  const head = `<div class="section-title" style="margin-top:8px"><h2 style="font-size:28px">Motori</h2>${tabs}</div>`;
  app.innerHTML = shell('motors', head + loading());
  bindShell();
  let feed;
  try { feed = await api('motors', { sport }); }
  catch (e) {
    document.getElementById('content').innerHTML = head + empty(e.message, `<button class="btn" data-act="sport" data-v="${sport}">Riprova</button>`);
    return;
  }
  S.motors = [...feed.live, ...feed.onDemand];
  const label = sport === 'f1' ? 'Formula 1' : 'MotoGP';
  const when = (m) => {
    const d = m.status === 'upcoming' ? (m.validFrom || m.date) : m.date;
    if (m.status === 'live') return 'In onda ora';
    if (!d) return m.status === 'blocked' ? 'Non disponibile' : '';
    return new Date(d).toLocaleString('it-IT', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  };
  const card = (m, i) => `
    <button class="wide" data-act="rsi" data-i="${i}">
      <span class="art">${m.image ? `<img src="${esc(m.image)}" alt="" loading="lazy">` : ''}
        ${m.status === 'live' ? '<span class="badge live" style="top:8px;left:8px">IN DIRETTA</span>' : ''}
        ${m.status === 'upcoming' ? '<span class="badge" style="top:8px;left:8px">IN PROGRAMMA</span>' : ''}
        ${m.blockReason === 'GEOBLOCK' ? '<span class="badge" style="bottom:8px;left:8px">Solo in Svizzera</span>' : ''}
        ${m.durationMs && m.status !== 'live' ? `<span class="badge" style="bottom:8px;right:8px">${fmt(m.durationMs)}</span>` : ''}
      </span>
      <span class="t">${esc(m.title)}</span>
      <span class="m">${esc(when(m))}</span>
    </button>`;
  const n = feed.live.length;
  document.getElementById('content').innerHTML = head + `
    <div class="section-title"><h2>Dirette e prossimi appuntamenti</h2><span class="hint">Contenuti RSI</span></div>
    ${n ? `<div class="wide-grid">${feed.live.map((m, i) => card(m, i)).join('')}</div>` : `<p class="hint">Nessuna diretta ${label} in programma sulla RSI.</p>`}
    <div class="section-title"><h2>Highlight, repliche e servizi</h2></div>
    ${feed.onDemand.length ? `<div class="wide-grid">${feed.onDemand.map((m, i) => card(m, i + n)).join('')}</div>` : `<p class="hint">Nessun contenuto ${label} trovato nell'archivio RSI.</p>`}`;
}

async function openRsi(i) {
  const m = S.motors?.[i];
  if (!m) return;
  if (m.status === 'upcoming') { toast('Diretta in programma: torna all\'orario indicato.'); return; }
  try {
    toast('Chiedo il flusso alla RSI…');
    const session = await api('rsi/play', { ...m, isLive: m.status === 'live' });
    document.getElementById('toast').hidden = true;
    startSession(session);
  } catch (e) { toast(e.message); }
}

// ---------- Impostazioni ----------

async function renderSettings() {
  if (isKid()) { location.hash = '#/home'; return; }
  await refreshState();
  try { S.library = await api('library/info'); } catch { S.library = null; }
  if (!S.update) S.update = await api('update/status');
  const profiles = S.state.profiles;
  if (!profiles.some((p) => p.id === S.settingsEditing)) S.settingsEditing = S.state.activeId;
  const p = profiles.find((x) => x.id === S.settingsEditing);
  const f = (id, label, value, placeholder, hint) => `
    <label class="field">${label}<input id="${id}" value="${esc(value)}" placeholder="${esc(placeholder)}" autocomplete="off" spellcheck="false"><span>${esc(hint)}</span></label>`;

  app.innerHTML = shell('settings', `
    <div class="section-title" style="margin-top:8px"><h2 style="font-size:28px">Impostazioni</h2></div>
    <div class="settings">
      <aside aria-label="Profili">
        <p class="label-up">Profili</p>
        ${profiles.map((x) => `
          <button class="pitem" data-act="edit-profile" data-id="${esc(x.id)}" aria-current="${x.id === p.id}">
            ${avatar(x, 36)}<span>${esc(x.name)}<small>${x.isKid ? 'Bambini' : 'Standard'}${x.id === S.state.activeId ? ' · in uso' : ''}</small></span>
          </button>`).join('')}
        <button class="dashed" data-act="new-profile" data-kid="0">+ Nuovo profilo</button>
        <button class="dashed" data-act="new-profile" data-kid="1">+ Nuovo profilo bambini</button>
        <button class="btn" data-act="set-pin" style="justify-content:center">${S.state.hasPin ? 'Cambia PIN genitori' : 'Imposta PIN genitori'}</button>
      </aside>
      <div class="form">
        <p class="label-up">${p.isKid ? 'Profilo bambini' : 'Profilo standard'}</p>
        <div class="fields">
          ${f('f-name', 'Nome del profilo', p.name, 'Es. Alberto', 'Come appare in «Chi guarda?»')}
          ${f('f-base', 'Base URL del server', p.baseUrl, 'http://server-vod.esempio', 'Il tuo backend: /api/search, /api/load, /api/get_links')}
          ${f('f-source', 'Dominio sorgente di film e serie', p.sourceDomain, 'sito-sorgente.esempio', 'Scrivilo a mano · inviato come parametro "domain"')}
          ${f('f-iframe', 'Dominio iframe', p.iframeDomain, 'dominio-iframe.esempio', 'iframe_domain di get_links e Referer del player')}
          ${f('f-tmdb', 'Chiave API TMDB', p.tmdbKey, 'chiave v3 oppure token v4', p.isKid ? 'Obbligatoria per il filtro bambini' : 'Locandine, trame ed episodi in italiano (themoviedb.org)')}
        </div>
        <div class="actions">
          <button class="btn primary" data-act="save-profile">Salva</button>
          <button class="btn" data-act="verify">Verifica connessione</button>
          ${p.id !== S.state.activeId ? '<button class="btn" data-act="use-profile">Usa questo profilo</button>' : ''}
          <button class="btn" data-act="clear-history">Cancella cronologia</button>
          ${p.id !== S.state.activeId ? '<button class="btn danger" data-act="delete-profile" style="margin-left:auto">Elimina profilo</button>' : ''}
        </div>
        <p class="feedback" id="feedback"></p>
        ${libraryCard(p)}
        ${updateCard()}
      </div>
    </div>`);
  bindShell();
}

function libraryCard(p) {
  const L = S.library;
  if (!L?.available) return '';
  const inUse = p.baseUrl === L.url;
  return `<section class="card" aria-labelledby="lib-t">
      <p class="label-up" id="lib-t">Libreria del Mac</p>
      <p style="margin:0">I tuoi video nella cartella <b>${esc(L.folder.replace(/^\/Users\/[^/]+/, '~'))}</b>: ${L.movies} film e ${L.series} serie.</p>
      <p class="hint" style="margin:0">File .mp4, .m4v, .mov o .m3u8. Nomi come «Titolo (2010).mp4» per i film e «Serie/S01E01.mp4» per le serie; trovi le istruzioni nel file LEGGIMI della cartella.</p>
      <div class="actions">
        ${inUse ? '<span class="hint" style="color:var(--watched);align-self:center">✓ Questo profilo usa la libreria</span>'
                : '<button class="btn primary" data-act="use-library">Usa la libreria per questo profilo</button>'}
        <button class="btn" data-act="open-library">Apri la cartella</button>
      </div>
    </section>`;
}

function updateCard() {
  const u = S.update || {};
  const cur = S.state.version;
  let line = `Versione installata: <b>${esc(cur)}</b>`;
  let buttons = '<button class="btn" data-act="update-check">Controlla aggiornamenti</button>';
  let extra = '';
  if (u.phase === 'checking') line += ' · controllo in corso…';
  if (u.phase === 'uptodate') line += ' · è l\'ultima versione';
  if (u.phase === 'available') {
    line += ` · <span style="color:var(--accent)">disponibile la versione ${esc(u.latest)}</span>`;
    buttons = '<button class="btn primary" data-act="update-download">Scarica e installa</button>';
    if (u.notes) extra = `<p class="hint" style="margin:0">${esc(u.notes)}</p>`;
  }
  if (u.phase === 'downloading') {
    buttons = '';
    extra = `<div class="progress" aria-label="Avanzamento download"><span style="width:${Math.round((u.progress || 0) * 100)}%"></span></div>
             <p class="hint" style="margin:0">Scarico l'aggiornamento… ${Math.round((u.progress || 0) * 100)}%</p>`;
  }
  if (u.phase === 'ready') {
    buttons = '<button class="btn primary" data-act="restart">Riavvia e aggiorna</button>';
    extra = '<p class="hint" style="margin:0;color:var(--watched)">Aggiornamento scaricato: si installa al riavvio.</p>';
  }
  if (u.problem) extra += `<p class="feedback err">${esc(u.problem)}</p>`;
  if (u.translocated) extra += '<p class="feedback err">Sposta VOD nella cartella Applicazioni: da Download macOS non permette di aggiornarla.</p>';
  if (!u.bundle && cur === 'dev') extra += '<p class="hint" style="margin:0">Versione di sviluppo: l\'aggiornamento automatico funziona solo con VOD.app.</p>';
  return `<section class="card" id="update-card" aria-labelledby="upd-t">
      <p class="label-up" id="upd-t">Aggiornamenti</p>
      <p style="margin:0">${line}</p>${extra}
      <div class="actions">${buttons}<button class="btn ghost" data-act="restart-only">Riavvia il programma</button></div>
    </section>`;
}

function paintUpdate() {
  const card = document.getElementById('update-card');
  if (card) card.outerHTML = updateCard();
}

function feedback(msg, err) {
  const el = document.getElementById('feedback');
  if (el) { el.textContent = msg; el.className = 'feedback ' + (err ? 'err' : 'ok'); }
}

function formValues() {
  const v = (id) => document.getElementById(id)?.value ?? '';
  return { id: S.settingsEditing, name: v('f-name'), baseUrl: v('f-base'), sourceDomain: v('f-source'), iframeDomain: v('f-iframe'), tmdbKey: v('f-tmdb') };
}

async function saveProfile(quiet) {
  const r = await api('profiles/update', formValues());
  S.browse = {};
  await refreshState();
  if (!quiet) { await renderSettings(); feedback(`Profilo «${r.profile.name}» salvato.`); }
  return r.profile;
}

async function updateLoop() {
  if (S.update?.phase !== 'downloading') return;
  S.update = await api('update/status');
  paintUpdate();
  if (S.update.phase === 'downloading') setTimeout(updateLoop, 400);
  else if (S.update.phase === 'ready') restartCountdown(true);
}

/** Conto alla rovescia prima del riavvio, con "Riavvia ora" e "Annulla". */
function restartCountdown(withUpdate) {
  let left = 5;
  const paint = () => openModal(`
    <div class="modal restart" role="alertdialog" aria-modal="true" aria-labelledby="rs-t">
      <h2 id="rs-t">${withUpdate ? 'Aggiornamento pronto' : 'Riavvio del programma'}</h2>
      <p>${withUpdate ? 'VOD si riavvia per installare la nuova versione.' : 'VOD si chiude e si riapre.'}</p>
      <div class="count" aria-live="assertive">${left}</div>
      <div class="actions" style="justify-content:center">
        <button class="btn primary" data-act="restart-now">Riavvia ora</button>
        <button class="btn" data-act="restart-cancel">Annulla</button>
      </div>
    </div>`, () => clearInterval(timer));
  paint();
  modalRoot.querySelector('[data-act="restart-now"]')?.focus();
  const timer = setInterval(() => {
    left -= 1;
    if (left <= 0) { clearInterval(timer); doRestart(); } else {
      const c = modalRoot.querySelector('.count');
      if (c) c.textContent = left; else clearInterval(timer);
    }
  }, 1000);
  S.restartTimer = timer;
}

async function doRestart() {
  clearInterval(S.restartTimer);
  openModal('<div class="modal"><div class="spinner"></div><p>Riavvio in corso…</p></div>');
  try { await api('restart'); } catch { /* il programma si sta chiudendo */ }
}

// ---------- PIN ----------

function pinPad({ title, subtitle, onComplete }) {
  let digits = '';
  let error = '';
  const paint = () => {
    openModal(`
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="pin-t">
        <h2 id="pin-t">${esc(title)}</h2>
        ${subtitle ? `<p>${esc(subtitle)}</p>` : ''}
        <div class="dots" aria-label="${digits.length} cifre inserite">${[0, 1, 2, 3].map((i) => `<span class="${i < digits.length ? 'on' : ''}"></span>`).join('')}</div>
        ${error ? `<p class="pin-error">${esc(error)}</p>` : ''}
        <div class="keypad">${['1', '2', '3', '4', '5', '6', '7', '8', '9', '⌫', '0', '✕'].map((k) => `<button data-pin="${k}" aria-label="${k === '⌫' ? 'Cancella' : k === '✕' ? 'Annulla' : k}">${k}</button>`).join('')}</div>
        <p>Puoi usare anche i tasti numerici · Esc per annullare</p>
      </div>`);
  };
  const press = (k) => {
    if (k === '✕') { closeModal(); return; }
    if (k === '⌫') { digits = digits.slice(0, -1); paint(); return; }
    if (!/^\d$/.test(k) || digits.length >= 4) return;
    digits += k;
    if (digits.length === 4) {
      const v = digits;
      digits = '';
      error = '';
      paint();
      onComplete(v, (msg) => { error = msg; paint(); });
    } else paint();
  };
  S.pinPress = press;
  paint();
}

function setupPin(then) {
  pinPad({
    title: 'Nuovo PIN genitori',
    subtitle: '4 cifre: servirà per uscire dai profili bambini',
    onComplete: (first) => {
      pinPad({
        title: 'Conferma il PIN',
        subtitle: 'Inserisci di nuovo le stesse 4 cifre',
        onComplete: async (second, setError) => {
          if (second !== first) { setError('I due PIN non coincidono: chiudi e riprova.'); return; }
          try {
            await api('pin', { pin: second });
            closeModal();
            await refreshState();
            if (then) await then(); else { await renderSettings(); feedback('PIN genitori salvato.'); }
          } catch (e) { setError(e.message); }
        },
      });
    },
  });
}

async function createProfile(kid) {
  const r = await api('profiles/create', { kid, templateId: S.settingsEditing });
  if (r.pinRequired) {
    setupPin(() => createProfile(kid));
    return;
  }
  S.settingsEditing = r.profile.id;
  await renderSettings();
  feedback(kid ? 'Profilo bambini creato: vede solo titoli per bambini verificati con TMDB. Cambia il nome e Salva.' : 'Nuovo profilo creato: cambia nome e impostazioni, poi Salva.');
}

// ---------- finestre modali ----------

let modalClose = null;
function openModal(html, onClose) {
  if (onClose) modalClose = onClose;
  modalRoot.innerHTML = `<div class="modal-back">${html}</div>`;
}
function closeModal() {
  if (!modalRoot.innerHTML) return;
  modalRoot.innerHTML = '';
  S.pinPress = null;
  if (modalClose) { const c = modalClose; modalClose = null; c(); }
}

// ---------- azioni (un solo gestore per tutti i clic) ----------

let confirmDelete = false, confirmClear = false;

document.addEventListener('click', async (e) => {
  const pinKey = e.target.closest('[data-pin]');
  if (pinKey && S.pinPress) { S.pinPress(pinKey.dataset.pin); return; }
  const el = e.target.closest('[data-act]');
  if (!el) {
    if (e.target.classList.contains('modal-back') && !S.restartTimer) closeModal();
    return;
  }
  const act = el.dataset.act;
  const d = el.dataset;
  try {
    switch (act) {
      case 'reload': return render();
      case 'profiles': location.hash = '#/profiles'; return;
      case 'switch': return await switchProfile(d.id);
      case 'cw': return openContinue(Number(d.i));
      case 'research': return runSearch(location.hash.split('/')[2] || 'all');
      case 'back': return history.length > 1 ? history.back() : (location.hash = '#/home');
      case 'season': S.detailSeason[S.detail.detail.slug] = Number(d.i); return paintDetail();
      case 'play-resume': { const r = S.detail.resume; return play(S.detail.detail.slug, r.season, r.episode, r.positionMs); }
      case 'play-start': { const r = S.detail.resume; return play(S.detail.detail.slug, r.season, r.episode, 0); }
      case 'play-ep':
        if (e.target.closest('[data-act="toggle-ep"]')) return;
        return play(S.detail.detail.slug, Number(d.s), Number(d.e), null);
      case 'toggle-ep':
        e.stopPropagation();
        await api('watched', { slug: S.detail.detail.slug, season: Number(d.s), episode: Number(d.e), watched: d.w !== '1' });
        return reloadDetail();
      case 'watched-movie': {
        const p = S.detail.progress[`vod:${S.detail.detail.slug}`];
        await api('watched', { slug: S.detail.detail.slug, watched: !p?.watched });
        return reloadDetail();
      }
      case 'fav': {
        const x = S.detail.detail;
        await api('favorite', { slug: x.slug, title: x.title, image: x.image, type: x.type, fav: !S.detail.isFavorite });
        S.detail.isFavorite = !S.detail.isFavorite;
        return paintDetail();
      }
      case 'source': { const s = await api('choose', { url: S.links[Number(d.i)].url }); closeModal(); return startSession(s); }
      case 'close-modal': return closeModal();
      case 'exit': return exitPlayer();
      case 'pp': return S.player?.toggle();
      case 'rew': return S.player?.seekBy(-10);
      case 'fwd': return S.player?.seekBy(10);
      case 'mute': return S.player?.mute();
      case 'full': return toggleFullscreen();
      case 'next': return goNext();
      case 'cancel-next': return S.cancelNext?.();
      case 'retry-video': return S.player?.retry();
      case 'sport': location.hash = `#/motors/${d.v}`; if (location.hash === `#/motors/${d.v}`) render(); return;
      case 'rsi': return openRsi(Number(d.i));
      case 'edit-profile': S.settingsEditing = d.id; confirmDelete = confirmClear = false; return renderSettings();
      case 'new-profile': return createProfile(d.kid === '1');
      case 'set-pin': return setupPin();
      case 'save-profile': return await saveProfile();
      case 'verify': {
        await saveProfile(true);
        feedback('Verifico…');
        const r = await api('verify', { id: S.settingsEditing });
        return feedback(r.message, r.error);
      }
      case 'use-profile': await saveProfile(true); return switchProfile(S.settingsEditing);
      case 'clear-history':
        if (!confirmClear) { confirmClear = true; el.textContent = 'Conferma: cancella cronologia'; return feedback('Premi di nuovo per cancellare la cronologia di questo profilo.', true); }
        confirmClear = false;
        await api('history/clear', { id: S.settingsEditing });
        el.textContent = 'Cancella cronologia';
        return feedback('Cronologia cancellata.');
      case 'delete-profile':
        if (!confirmDelete) { confirmDelete = true; el.textContent = 'Conferma eliminazione'; return feedback('Premi di nuovo per eliminare il profilo con preferiti e cronologia.', true); }
        confirmDelete = false;
        await api('profiles/delete', { id: S.settingsEditing });
        S.settingsEditing = S.state.activeId;
        await renderSettings();
        return feedback('Profilo eliminato.');
      case 'use-library':
        document.getElementById('f-base').value = S.library.url;
        await saveProfile();
        return feedback('Fatto: questo profilo ora mostra i video della libreria. Il dominio iframe può restare vuoto.');
      case 'open-library': return api('library/open');
      case 'update-check': S.update = { ...S.update, phase: 'checking' }; paintUpdate(); S.update = await api('update/check'); return paintUpdate();
      case 'update-download': S.update = await api('update/download'); paintUpdate(); return updateLoop();
      case 'restart': return restartCountdown(true);
      case 'restart-only': return restartCountdown(false);
      case 'restart-now': return doRestart();
      case 'restart-cancel': clearInterval(S.restartTimer); S.restartTimer = null; return closeModal();
    }
  } catch (err) {
    if (location.hash.startsWith('#/settings')) feedback(err.message, true); else toast(err.message);
  }
});

document.addEventListener('keydown', (e) => {
  if (S.pinPress) {
    if (/^\d$/.test(e.key)) { S.pinPress(e.key); e.preventDefault(); return; }
    if (e.key === 'Backspace') { S.pinPress('⌫'); e.preventDefault(); return; }
  }
  if (e.key === 'Escape') {
    if (modalRoot.innerHTML) { if (!S.restartTimer) closeModal(); return; }
    if (location.hash === '#/player') { if (document.fullscreenElement) return; exitPlayer(); return; }
    if (location.hash.startsWith('#/detail')) { history.back(); return; }
  }
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'f') {
    e.preventDefault();
    const s = document.getElementById('search');
    if (s) s.focus(); else location.hash = '#/browse/all';
    return;
  }
  if (location.hash === '#/player' && S.player && !modalRoot.innerHTML) {
    const k = e.key.toLowerCase();
    if (k === ' ' || k === 'k') { e.preventDefault(); S.player.toggle(); }
    else if (k === 'arrowleft' || k === 'j') { e.preventDefault(); S.player.seekBy(-10); }
    else if (k === 'arrowright' || k === 'l') { e.preventDefault(); S.player.seekBy(10); }
    else if (k === 'f') toggleFullscreen();
    else if (k === 'm') S.player.mute();
  }
  if (e.key === 'Enter' && e.target.classList?.contains('episode')) e.target.click();
});

window.addEventListener('hashchange', render);

// ---------- avvio ----------

(async function boot() {
  try {
    await refreshState();
  } catch (e) {
    app.innerHTML = empty('Il programma non risponde: chiudilo e riaprilo.');
    return;
  }
  const target = S.state.profiles.length > 1 ? '#/profiles' : '#/home';
  if (location.hash !== target) location.hash = target; else render();
  // Controllo silenzioso degli aggiornamenti: pallino sul dock se ce n'è uno
  try {
    S.update = await api('update/check');
    document.querySelectorAll('.dock a[href="#/settings"]').forEach((a) => {
      if ((S.update.phase === 'available') && !a.querySelector('.dot')) a.insertAdjacentHTML('beforeend', '<span class="dot"></span>');
    });
  } catch { /* nessuna rete: si riproverà dalle Impostazioni */ }
})();
