/* KidQuest – daily schedules, star points & rewards for kids.
 * Plain JS, no build step. All data lives in localStorage on this device.
 */
(() => {
  'use strict';

  /* ===================== Constants ===================== */
  const APP_VERSION = '1.3.0'; // bump on every release (keep sw.js CACHE in sync)
  const STORE_KEY = 'kidquest:v1';
  const SESSION_KEY = 'kidquest:unlocked';

  const AVATARS = ['🦊', '🐼', '🦄', '🐯', '🐸', '🐵', '🐧', '🦁', '🐨', '🐰', '🐙', '🦖', '🐶', '🐱', '🦋', '🐳', '🐲', '🦉', '🐞', '🚀'];
  const COLORS = ['#FF7A59', '#FFB020', '#2EC4B6', '#4D96FF', '#9B5DE5', '#F15BB5', '#00B86B', '#FF5D73'];
  const TASK_ICONS = ['🪥', '🛏️', '👕', '🥣', '🎒', '✏️', '📚', '📖', '🧸', '🍽️', '🛁', '🚿', '🌙', '🐕', '🌱', '🎹', '⚽', '🧹', '🧺', '💧', '🥦', '🙏', '👟', '🧩', '🎨', '🏃', '🗑️', '💊'];
  const REWARD_ICONS = ['🍦', '🎮', '📺', '🍪', '🤖', '🕹️', '🎬', '🎨', '🧁', '🍕', '🍫', '🎡', '🛝', '🏊', '🚲', '🧸', '🎁', '🌙', '📱', '🎟️', '⭐'];
  const SLOTS = [
    { id: 'morning', label: 'Morning', icon: '🌅' },
    { id: 'afternoon', label: 'Afternoon', icon: '☀️' },
    { id: 'evening', label: 'Evening', icon: '🌙' },
  ];
  const DAY_SHORT = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const WEEK_ORDER = [1, 2, 3, 4, 5, 6, 0];
  const TASK_SUGGESTIONS = [
    ['Brush teeth', '🪥', 'morning'], ['Make your bed', '🛏️', 'morning'], ['Get dressed', '👕', 'morning'],
    ['Eat breakfast', '🥣', 'morning'], ['Pack school bag', '🎒', 'morning'], ['Homework', '✏️', 'afternoon'],
    ['Practice instrument', '🎹', 'afternoon'], ['Water the plants', '🌱', 'afternoon'], ['Set the table', '🍽️', 'evening'],
    ['Tidy your room', '🧸', 'evening'], ['Read 20 minutes', '📖', 'evening'], ['Bath time', '🛁', 'evening'],
  ];
  const REWARD_SUGGESTIONS = [
    ['Ice cream treat', '🍦', 50], ['30 min screen time', '🎮', 30], ['Pick the movie', '🎬', 100],
    ['Stay up 15 min later', '🌙', 60], ['Trip to the park', '🛝', 80], ['Small toy', '🧸', 200],
  ];
  const BADGES = [
    { id: 'first', icon: '🌱', name: 'First Quest', test: s => s.tasks >= 1 },
    { id: 'bee', icon: '🐝', name: 'Busy Bee', test: s => s.tasks >= 25 },
    { id: 'master', icon: '🧙', name: 'Quest Master', test: s => s.tasks >= 100 },
    { id: 'fire', icon: '🔥', name: 'On Fire', test: s => s.best >= 3 },
    { id: 'week', icon: '🚀', name: 'Super Week', test: s => s.best >= 7 },
    { id: 'treat', icon: '🎁', name: 'Treat Time', test: s => s.rewards >= 1 },
    { id: 'saver', icon: '🐷', name: 'Star Saver', test: s => s.balance >= 50 },
    { id: 'legend', icon: '👑', name: 'Legend', test: s => s.earned >= 500 },
  ];
  const CONFETTI_COLORS = ['#FF7A59', '#FFB020', '#2EC4B6', '#4D96FF', '#9B5DE5', '#F15BB5', '#00B86B'];
  const CHECK_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  /* ===================== Utilities ===================== */
  const pad = n => String(n).padStart(2, '0');
  const keyOf = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const todayKey = () => keyOf(new Date());
  const parseKey = k => { const [y, m, d] = k.split('-').map(Number); return new Date(y, m - 1, d); };
  const addDays = (k, n) => { const d = parseKey(k); d.setDate(d.getDate() + n); return keyOf(d); };
  const weekday = k => parseKey(k).getDay();
  const weekStart = k => addDays(k, -((weekday(k) + 6) % 7)); // Monday
  const uid = () => Math.random().toString(36).slice(2, 9) + Date.now().toString(36).slice(-4);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const clamp = (n, a, b) => Math.min(b, Math.max(a, n));
  const plural = (n, w, many) => `${n} ${n === 1 ? w : many || `${w}s`}`;
  const reducedMotion = () => window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const vibrate = ms => { try { navigator.vibrate && navigator.vibrate(ms); } catch (e) { /* ignore */ } };

  function dayTitle(k) {
    const t = todayKey();
    if (k === t) return 'Today';
    if (k === addDays(t, -1)) return 'Yesterday';
    if (k === addDays(t, 1)) return 'Tomorrow';
    return parseKey(k).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' });
  }
  function fmtTime(hm) {
    if (!hm) return '';
    const [h, m] = hm.split(':').map(Number);
    const d = new Date(); d.setHours(h, m, 0, 0);
    return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  }
  function fmtWhen(ts) {
    const d = new Date(ts), k = keyOf(d), t = todayKey();
    const time = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    if (k === t) return `Today, ${time}`;
    if (k === addDays(t, -1)) return `Yesterday, ${time}`;
    return `${d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}, ${time}`;
  }
  const currentSlot = () => { const h = new Date().getHours(); return h < 12 ? 'morning' : h < 17 ? 'afternoon' : 'evening'; };
  function daysSummary(days) {
    const s = [...days].sort().join(',');
    if (days.length === 7) return 'Every day';
    if (s === '1,2,3,4,5') return 'Weekdays';
    if (s === '0,6') return 'Weekends';
    return WEEK_ORDER.filter(d => days.includes(d)).map(d => DAY_SHORT[d]).join(', ');
  }
  function sessionGet() { try { return sessionStorage.getItem(SESSION_KEY) === '1'; } catch (e) { return false; } }
  function sessionSet(v) { try { v ? sessionStorage.setItem(SESSION_KEY, '1') : sessionStorage.removeItem(SESSION_KEY); } catch (e) { /* ignore */ } }

  /* ===================== State ===================== */
  function defaultState() {
    return {
      version: 1,
      onboarded: false,
      settings: { pin: '', requireApproval: true, sound: true },
      children: [], tasks: [], rewards: [],
      completions: {}, // { 'YYYY-MM-DD': { childId: { taskId: timestamp } } }
      ledger: [],      // { id, childId, amount, reason, type, ref?, day?, at }
      redemptions: [], // { id, childId, rewardId, title, icon, cost, status, at, resolvedAt? }
    };
  }
  function normalize(s) {
    const d = defaultState();
    if (!s || typeof s !== 'object') return d;
    const out = { ...d, ...s, settings: { ...d.settings, ...(s.settings || {}) } };
    for (const k of ['children', 'tasks', 'rewards', 'ledger', 'redemptions']) if (!Array.isArray(out[k])) out[k] = [];
    if (!out.completions || typeof out.completions !== 'object') out.completions = {};
    out.tasks.forEach(t => { t.childIds = t.childIds || []; t.days = t.days || [0, 1, 2, 3, 4, 5, 6]; });
    out.rewards.forEach(r => { r.childIds = r.childIds || []; });
    return out;
  }
  function load() {
    try { const raw = localStorage.getItem(STORE_KEY); return raw ? normalize(JSON.parse(raw)) : defaultState(); }
    catch (e) { return defaultState(); }
  }
  let state = load();
  let saveWarned = false;
  function save() {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); }
    catch (e) { if (!saveWarned) { saveWarned = true; toast('⚠️ Could not save on this device'); } }
  }

  function sampleState() {
    const s = defaultState();
    s.onboarded = true;
    const mia = { id: uid(), name: 'Mia', avatar: '🦄', color: '#9B5DE5' };
    const leo = { id: uid(), name: 'Leo', avatar: '🦖', color: '#2EC4B6' };
    s.children = [mia, leo];
    const both = [mia.id, leo.id], all = [0, 1, 2, 3, 4, 5, 6], wk = [1, 2, 3, 4, 5];
    const created = addDays(todayKey(), -21);
    const T = (title, icon, points, slot, time, days, childIds) => ({ id: uid(), title, icon, points, slot, time, days, childIds, created });
    s.tasks = [
      T('Brush teeth', '🪥', 2, 'morning', '07:00', all, both),
      T('Make your bed', '🛏️', 3, 'morning', '07:10', all, both),
      T('Get dressed', '👕', 2, 'morning', '07:20', wk, both),
      T('Pack school bag', '🎒', 3, 'morning', '07:40', wk, both),
      T('Homework', '✏️', 5, 'afternoon', '16:00', wk, both),
      T('Piano practice', '🎹', 5, 'afternoon', '17:00', [1, 3, 5], [mia.id]),
      T('Feed the dog', '🐕', 3, 'afternoon', '17:30', all, [leo.id]),
      T('Tidy up toys', '🧸', 3, 'evening', '19:00', all, both),
      T('Read a book', '📖', 4, 'evening', '19:30', all, both),
      T('Brush teeth', '🪥', 2, 'evening', '20:00', all, both),
    ];
    const R = (title, icon, cost, childIds) => ({ id: uid(), title, icon, cost, childIds });
    s.rewards = [
      R('30 min screen time', '🎮', 30, both),
      R('Ice cream treat', '🍦', 50, both),
      R('Trip to the park', '🛝', 80, [leo.id]),
      R('Pick the movie', '🎬', 120, both),
      R('New art supplies', '🎨', 200, [mia.id]),
      R('Surprise gift', '🎁', 300, both),
    ];
    // Some history so the demo feels alive (deterministic pseudo-random).
    let seed = 7;
    const rnd = () => { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; };
    const prev = state; state = s; // tasksFor() reads global state
    for (const [kid, fullDays, rate] of [[mia, 5, 0.75], [leo, 2, 0.6]]) {
      for (let i = 1; i <= 8; i++) {
        const k = addDays(todayKey(), -i);
        const at = parseKey(k).getTime() + 18 * 3600e3;
        for (const t of tasksFor(kid.id, k)) {
          if (i <= fullDays || rnd() < rate) {
            ((s.completions[k] ||= {})[kid.id] ||= {})[t.id] = at;
            s.ledger.push({ id: uid(), childId: kid.id, amount: t.points, reason: `${t.icon} ${t.title}`, type: 'task', ref: `task:${k}:${kid.id}:${t.id}`, day: k, at });
          }
        }
      }
    }
    state = prev;
    const screen = s.rewards[0], ice = s.rewards[1];
    const r1 = { id: uid(), childId: leo.id, rewardId: screen.id, title: screen.title, icon: screen.icon, cost: screen.cost, status: 'approved', at: Date.now() - 3 * 864e5, resolvedAt: Date.now() - 3 * 864e5 };
    const r2 = { id: uid(), childId: mia.id, rewardId: ice.id, title: ice.title, icon: ice.icon, cost: ice.cost, status: 'pending', at: Date.now() - 2 * 3600e3 };
    s.redemptions.push(r1, r2);
    s.ledger.push(
      { id: uid(), childId: leo.id, amount: -screen.cost, reason: `${screen.icon} ${screen.title}`, type: 'reward', ref: `redeem:${r1.id}`, at: r1.at },
      { id: uid(), childId: mia.id, amount: -ice.cost, reason: `${ice.icon} ${ice.title}`, type: 'reward', ref: `redeem:${r2.id}`, at: r2.at },
      { id: uid(), childId: leo.id, amount: 5, reason: '🌟 Helped grandma with groceries', type: 'bonus', at: Date.now() - 864e5 },
    );
    return s;
  }

  /* ===================== Selectors & mutations ===================== */
  const getChild = id => state.children.find(c => c.id === id);
  const getTask = id => state.tasks.find(t => t.id === id);
  const getReward = id => state.rewards.find(r => r.id === id);
  const balance = id => state.ledger.reduce((s, e) => (e.childId === id ? s + e.amount : s), 0);
  const pendingRedemptions = () => state.redemptions.filter(r => r.status === 'pending');
  const slotIndex = s => SLOTS.findIndex(x => x.id === s);
  const byTaskOrder = (a, b) => slotIndex(a.slot) - slotIndex(b.slot) || (a.time || '99').localeCompare(b.time || '99') || a.title.localeCompare(b.title);
  function tasksFor(childId, k) {
    const wd = weekday(k);
    return state.tasks.filter(t => t.childIds.includes(childId) && t.days.includes(wd) && (!t.created || k >= t.created)).sort(byTaskOrder);
  }
  const isDone = (childId, taskId, k) => !!(state.completions[k] && state.completions[k][childId] && state.completions[k][childId][taskId]);
  function dayProgress(childId, k) {
    const ts = tasksFor(childId, k);
    return { done: ts.filter(t => isDone(childId, t.id, k)).length, total: ts.length };
  }
  const dayComplete = (childId, k) => { const p = dayProgress(childId, k); return p.total > 0 && p.done === p.total; };
  function streak(childId) {
    let k = todayKey(), n = dayComplete(childId, k) ? 1 : 0;
    for (let i = 0; i < 400; i++) {
      k = addDays(k, -1);
      const p = dayProgress(childId, k);
      if (!p.total) { if (!state.tasks.some(t => !t.created || k >= t.created)) break; continue; }
      if (p.done === p.total) n++; else break;
    }
    return n;
  }
  function bestStreak(childId) {
    const first = state.tasks.reduce((m, t) => (t.created && t.created < m ? t.created : m), todayKey());
    let best = 0, cur = 0;
    for (let k = first; k <= todayKey(); k = addDays(k, 1)) {
      const p = dayProgress(childId, k);
      if (!p.total) continue;
      if (p.done === p.total) best = Math.max(best, ++cur); else if (k !== todayKey()) cur = 0;
    }
    return best;
  }
  function childStats(childId) {
    const mine = state.ledger.filter(e => e.childId === childId);
    return {
      tasks: mine.filter(e => e.type === 'task').length,
      earned: mine.filter(e => e.type === 'task' || e.type === 'bonus').reduce((s, e) => s + e.amount, 0),
      rewards: state.redemptions.filter(r => r.childId === childId && r.status === 'approved').length,
      balance: balance(childId),
      streak: streak(childId),
      best: bestStreak(childId),
    };
  }

  function toggleTask(childId, taskId, k) {
    const t = getTask(taskId);
    if (!t) return false;
    const ref = `task:${k}:${childId}:${taskId}`;
    const kid = ((state.completions[k] ||= {})[childId] ||= {});
    if (kid[taskId]) {
      delete kid[taskId];
      state.ledger = state.ledger.filter(e => e.ref !== ref);
      save();
      return false;
    }
    kid[taskId] = Date.now();
    state.ledger.push({ id: uid(), childId, amount: t.points, reason: `${t.icon} ${t.title}`, type: 'task', ref, day: k, at: Date.now() });
    save();
    return true;
  }
  function redeem(childId, rewardId) {
    const r = getReward(rewardId);
    if (!r || balance(childId) < r.cost) return null;
    const id = uid();
    const status = state.settings.requireApproval ? 'pending' : 'approved';
    state.redemptions.push({ id, childId, rewardId, title: r.title, icon: r.icon, cost: r.cost, status, at: Date.now(), resolvedAt: status === 'approved' ? Date.now() : undefined });
    state.ledger.push({ id: uid(), childId, amount: -r.cost, reason: `${r.icon} ${r.title}`, type: 'reward', ref: `redeem:${id}`, at: Date.now() });
    save();
    return status;
  }
  function resolveRedemption(id, approve) {
    const x = state.redemptions.find(r => r.id === id);
    if (!x || x.status !== 'pending') return;
    x.status = approve ? 'approved' : 'declined';
    x.resolvedAt = Date.now();
    if (!approve) state.ledger.push({ id: uid(), childId: x.childId, amount: x.cost, reason: `↩️ Refund: ${x.title}`, type: 'refund', ref: `refund:${id}`, at: Date.now() });
    save();
  }
  function adjustPoints(childId, amount, reason) {
    state.ledger.push({ id: uid(), childId, amount, reason, type: amount >= 0 ? 'bonus' : 'penalty', at: Date.now() });
    save();
  }

  /* ===================== UI state & routing ===================== */
  const ui = { date: todayKey(), taskFilter: 'all', unlocked: sessionGet() };
  const app = document.getElementById('app');
  const route = () => location.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  const go = path => { const h = `#/${path}`; if (location.hash === h) render(); else location.hash = h; };

  function render() {
    const [view, a, b] = route();
    let html, accent = '';
    if (!state.onboarded) {
      html = viewWelcome();
    } else if (view === 'child' && getChild(a)) {
      const c = getChild(a);
      accent = c.color;
      html = viewChild(c, ['today', 'rewards', 'activity'].includes(b) ? b : 'today');
    } else if (view === 'parent') {
      if (!ui.unlocked && state.settings.pin) {
        history.replaceState(null, '', '#/');
        html = viewHome();
        setTimeout(() => enterParent(a || 'kids'), 0);
      } else {
        html = viewParent(['kids', 'tasks', 'rewards', 'inbox', 'settings'].includes(a) ? a : 'kids');
      }
    } else {
      html = viewHome();
    }
    document.body.style.setProperty('--accent', accent || '#FF7A59');
    const meta = $('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', accent || '#FF7A59');
    app.innerHTML = html;
  }

  /* ===================== Shared bits ===================== */
  function ring(pct, size = 64, stroke = 8, inner = '') {
    const r = (size - stroke) / 2, c = 2 * Math.PI * r, h = size / 2;
    return `<div class="ring" style="width:${size}px;height:${size}px">
      <svg viewBox="0 0 ${size} ${size}" width="${size}" height="${size}" aria-hidden="true">
        <circle cx="${h}" cy="${h}" r="${r}" class="ring-bg" stroke-width="${stroke}"/>
        <circle cx="${h}" cy="${h}" r="${r}" class="ring-fg" stroke-width="${stroke}" stroke-dasharray="${c.toFixed(2)}" stroke-dashoffset="${(c * (1 - clamp(pct, 0, 1))).toFixed(2)}" transform="rotate(-90 ${h} ${h})"/>
      </svg><span>${inner}</span></div>`;
  }
  const appFooter = () => `<footer class="app-footer">KidQuest v${APP_VERSION}</footer>`;
  const avatarDots = ids => ids.map(getChild).filter(Boolean).map(c => `<span class="avatar avatar-xs" style="--accent:${c.color}" title="${esc(c.name)}">${c.avatar}</span>`).join('');
  const typeIcon = { task: '✅', reward: '🎁', bonus: '🌟', penalty: '⚠️', refund: '↩️' };
  function activityList(entries, showKid) {
    if (!entries.length) return `<div class="empty small"><div class="empty-art">🌈</div><p>Nothing here yet.</p></div>`;
    return `<ul class="activity">${entries.map(e => {
      const kid = showKid ? getChild(e.childId) : null;
      return `<li>
        <span class="act-ico">${kid ? `<span class="avatar avatar-xs" style="--accent:${kid.color}">${kid.avatar}</span>` : typeIcon[e.type] || '⭐'}</span>
        <div class="grow"><b>${esc(e.reason)}</b><small>${kid ? `${esc(kid.name)} · ` : ''}${fmtWhen(e.at)}</small></div>
        <span class="amt ${e.amount >= 0 ? 'pos' : 'neg'}">${e.amount >= 0 ? '+' : '−'}${Math.abs(e.amount)}</span>
      </li>`;
    }).join('')}</ul>`;
  }

  /* ===================== Views: welcome & home ===================== */
  function viewWelcome() {
    return `<div class="screen welcome">
      <div class="welcome-art" aria-hidden="true">
        <span class="w1">🦄</span><span class="w2">⭐</span><span class="w3">🪥</span><span class="w4">🎁</span><span class="w5">🦖</span><span class="w6">📚</span>
        <div class="welcome-logo">⭐</div>
      </div>
      <h1 class="brand">Kid<span>Quest</span></h1>
      <p class="lead">Turn daily routines into an adventure. Kids tick off quests, earn stars and swap them for rewards you choose.</p>
      <ul class="feature-list">
        <li><span>🗓️</span><div><b>Daily schedules</b><small>Morning, afternoon &amp; evening routines</small></div></li>
        <li><span>⭐</span><div><b>Star points</b><small>You decide what each task is worth</small></div></li>
        <li><span>🎁</span><div><b>Rewards shop</b><small>Map rewards to each child's profile</small></div></li>
        <li><span>👧</span><div><b>Multiple kids</b><small>Own avatar, colour and progress</small></div></li>
      </ul>
      <div class="stack">
        <button type="button" class="btn btn-primary btn-lg" data-action="start-sample">✨ Try it with examples</button>
        <button type="button" class="btn btn-ghost btn-lg" data-action="start-fresh">Set up from scratch</button>
        <button type="button" class="restore-link" data-action="import">📥 Restore from a backup file</button>
      </div>
      ${appFooter()}
    </div>`;
  }

  function viewHome() {
    const k = todayKey();
    const pend = pendingRedemptions().length;
    const cards = state.children.map((c, i) => {
      const p = dayProgress(c.id, k), s = streak(c.id);
      const pct = p.total ? p.done / p.total : 0;
      const sub = !p.total ? 'Free day 🎈' : p.done === p.total ? 'All done! 🎉' : `${p.done} of ${p.total} done`;
      return `<button type="button" class="profile-card" style="--accent:${c.color};--i:${i}" data-action="open-child" data-id="${c.id}">
        <span class="profile-blob" aria-hidden="true"></span>
        <span class="avatar avatar-xl">${c.avatar}</span>
        <span class="profile-name">${esc(c.name)}</span>
        <span class="chip chip-star">⭐ ${balance(c.id)}</span>
        <span class="mini-bar"><i style="width:${pct * 100}%"></i></span>
        <span class="profile-sub">${sub}${s > 1 ? ` · 🔥${s}` : ''}</span>
      </button>`;
    }).join('');
    return `<div class="screen home">
      <header class="home-head">
        <div>
          <p class="eyebrow">${parseKey(k).toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}</p>
          <h1 class="brand">Kid<span>Quest</span></h1>
        </div>
        <button type="button" class="icon-btn parent-btn" data-action="parent" aria-label="Parent zone">🔒${pend ? `<b class="badge">${pend}</b>` : ''}</button>
      </header>
      ${state.children.length ? `
        <h2 class="home-q">Who's on a quest today?</h2>
        <div class="profile-grid">${cards}</div>
        <button type="button" class="parent-link" data-action="parent">👪 Parent zone${pend ? ` · <b>${plural(pend, 'request')}</b>` : ''}</button>`
      : `<div class="empty"><div class="empty-art">🧒</div><h2>No explorers yet</h2><p>Add a child profile in the parent zone to get started.</p>
          <button type="button" class="btn btn-primary" data-action="parent">Open parent zone</button></div>`}
      ${appFooter()}
    </div>`;
  }

  /* ===================== Views: child ===================== */
  function viewChild(c, tab) {
    const k = todayKey(), p = dayProgress(c.id, k), s = streak(c.id);
    let msg;
    if (!p.total) msg = 'No quests today – enjoy your free day! 🎈';
    else if (p.done === p.total) msg = 'You finished every quest today! 🌟';
    else msg = `${plural(p.total - p.done, 'quest')} left today${s > 0 ? ` · 🔥 ${s}-day streak` : ''}`;
    const body = tab === 'rewards' ? childRewards(c) : tab === 'activity' ? childActivity(c) : childToday(c);
    const item = (id, icon, label) => `<a class="nav-item ${tab === id ? 'active' : ''}" href="#/child/${c.id}/${id}" ${tab === id ? 'aria-current="page"' : ''}><span>${icon}</span><small>${label}</small></a>`;
    return `<div class="screen child">
      <header class="child-head">
        <div class="child-top">
          <button type="button" class="icon-btn on-accent" data-action="home" aria-label="Switch profile">
            <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <div class="chip chip-star big" id="balance" aria-label="${balance(c.id)} stars">⭐ <b>${balance(c.id)}</b></div>
        </div>
        <div class="child-hero">
          <span class="avatar avatar-lg">${c.avatar}</span>
          <div><h1>Hi, ${esc(c.name)}!</h1><p>${msg}</p></div>
        </div>
      </header>
      <div class="child-body">${body}${appFooter()}</div>
      <nav class="bottom-nav">${item('today', '🗓️', 'Quests')}${item('rewards', '🎁', 'Rewards')}${item('activity', '🏆', 'Trophies')}</nav>
    </div>`;
  }

  function childToday(c) {
    const k = ui.date, today = todayKey();
    const ws = weekStart(k);
    const strip = [...Array(7)].map((_, i) => addDays(ws, i)).map(d => {
      const p = dayProgress(c.id, d);
      const st = !p.total ? 'none' : p.done === p.total ? 'full' : p.done ? 'part' : d < today ? 'miss' : 'todo';
      return `<button type="button" class="day ${d === k ? 'sel' : ''} ${d === today ? 'today' : ''}" data-action="pick-day" data-day="${d}" aria-label="${dayTitle(d)}" ${d === k ? 'aria-current="date"' : ''}>
        <small>${DAY_SHORT[weekday(d)]}</small><b>${parseKey(d).getDate()}</b><i class="dot ${st}">${st === 'full' ? '★' : ''}</i>
      </button>`;
    }).join('');

    const tasks = tasksFor(c.id, k);
    const p = dayProgress(c.id, k);
    const earned = state.ledger.filter(e => e.childId === c.id && e.type === 'task' && e.day === k).reduce((s, e) => s + e.amount, 0);
    const isPast = k < today, isFuture = k > today;
    const canToggle = k === today || (isPast && ui.unlocked);
    const nowSlot = k === today ? currentSlot() : null;

    let note = '';
    if (isFuture) note = `<div class="banner info"><span>🗓️</span><p>Coming up! You can tick these off on the day.</p></div>`;
    else if (isPast && ui.unlocked) note = `<div class="banner info"><span>🔓</span><p>Parent mode: you can fix this day's quests.</p></div>`;
    else if (isPast) note = `<div class="banner info"><span>⏪</span><p>Looking back. Only today's quests can be ticked.</p></div>`;

    const groups = SLOTS.map(sl => {
      const ts = tasks.filter(t => t.slot === sl.id);
      if (!ts.length) return '';
      const d = ts.filter(t => isDone(c.id, t.id, k)).length;
      return `<section class="slot ${sl.id === nowSlot ? 'now' : ''}">
        <h3><span class="slot-ico">${sl.icon}</span>${sl.label}${sl.id === nowSlot ? '<em>Now</em>' : ''}<small>${d}/${ts.length}</small></h3>
        ${ts.map(t => {
          const done = isDone(c.id, t.id, k);
          return `<button type="button" class="task ${done ? 'done' : ''}" data-action="toggle-task" data-id="${t.id}" aria-pressed="${done}" ${canToggle ? '' : 'disabled'}>
            <span class="task-icon">${t.icon}</span>
            <span class="task-main"><span class="task-title">${esc(t.title)}</span>${t.time ? `<span class="task-meta">⏰ ${fmtTime(t.time)}</span>` : ''}</span>
            <span class="task-points">+${t.points} ⭐</span>
            <span class="task-check">${CHECK_SVG}</span>
          </button>`;
        }).join('')}
      </section>`;
    }).join('');

    return `<div class="week card">
        <div class="week-head">
          <button type="button" class="icon-btn small" data-action="week" data-dir="-1" aria-label="Previous week">‹</button>
          <b>${parseKey(ws).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}</b>
          <button type="button" class="icon-btn small" data-action="week" data-dir="1" aria-label="Next week">›</button>
        </div>
        <div class="days">${strip}</div>
      </div>
      <div class="day-head">
        <div>
          <h2>${dayTitle(k)}</h2>
          <p>${p.total ? `${p.done} of ${plural(p.total, 'quest')} · ${earned} ⭐ earned` : 'No quests scheduled'}</p>
          ${k !== today ? `<button type="button" class="chip chip-link" data-action="pick-day" data-day="${today}">↩ Back to today</button>` : ''}
        </div>
        ${p.total ? ring(p.done / p.total, 72, 9, p.done === p.total ? '🏆' : `${Math.round((p.done / p.total) * 100)}%`) : ''}
      </div>
      ${note}
      ${tasks.length ? groups : `<div class="empty"><div class="empty-art">🎈</div><h2>Free day!</h2><p>No quests on this day. Go play!</p></div>`}`;
  }

  function childRewards(c) {
    const bal = balance(c.id);
    const rewards = state.rewards.filter(r => r.childIds.includes(c.id)).sort((a, b) => a.cost - b.cost);
    const mine = state.redemptions.filter(r => r.childId === c.id).sort((a, b) => b.at - a.at);
    const pend = mine.filter(r => r.status === 'pending');
    const recent = mine.filter(r => r.status !== 'pending').slice(0, 6);
    const next = rewards.find(r => r.cost > bal);
    return `<div class="balance-hero card">
        <div class="coin" aria-hidden="true">⭐</div>
        <div><b>${bal}</b><small>stars to spend</small></div>
        ${next ? `<p class="goal">${next.cost - bal} more until ${next.icon} <b>${esc(next.title)}</b></p>` : rewards.length ? `<p class="goal">You can pick any reward! 🎉</p>` : ''}
      </div>
      ${pend.length ? `<h3 class="section-title">⏳ Waiting for a grown-up</h3>
        <div class="stack-sm">${pend.map(r => `<div class="card pending-row"><span class="row-icon">${r.icon}</span><div class="grow"><b>${esc(r.title)}</b><small>Asked ${fmtWhen(r.at)}</small></div><span class="chip">−${r.cost} ⭐</span></div>`).join('')}</div>` : ''}
      <h3 class="section-title">🎁 Rewards shop</h3>
      ${rewards.length ? `<div class="reward-grid">${rewards.map(r => {
        const ok = bal >= r.cost, pct = clamp(bal / r.cost, 0, 1);
        return `<div class="reward ${ok ? 'ready' : ''}">
          <div class="reward-icon">${r.icon}</div>
          <b class="reward-title">${esc(r.title)}</b>
          <span class="reward-cost">⭐ ${r.cost}</span>
          <span class="mini-bar"><i style="width:${pct * 100}%"></i></span>
          ${ok ? `<button type="button" class="btn btn-primary btn-small" data-action="redeem" data-id="${r.id}">Get it!</button>`
              : `<button type="button" class="btn btn-soft btn-small" disabled>${r.cost - bal} more ⭐</button>`}
        </div>`;
      }).join('')}</div>` : `<div class="empty small"><div class="empty-art">🎁</div><p>No rewards yet. Ask a grown-up to add some!</p></div>`}
      ${recent.length ? `<h3 class="section-title">🏅 Past rewards</h3>
        <ul class="activity">${recent.map(r => `<li><span class="act-ico">${r.icon}</span><div class="grow"><b>${esc(r.title)}</b><small>${r.status === 'approved' ? 'Enjoyed' : 'Not this time'} · ${fmtWhen(r.resolvedAt || r.at)}</small></div><span class="amt ${r.status === 'approved' ? 'neg' : ''}">${r.status === 'approved' ? `−${r.cost}` : '↩️'}</span></li>`).join('')}</ul>` : ''}`;
  }

  function childActivity(c) {
    const s = childStats(c.id);
    const entries = state.ledger.filter(e => e.childId === c.id).sort((a, b) => b.at - a.at).slice(0, 40);
    return `<div class="stats">
        <div class="stat card"><span>🔥</span><b>${s.streak}</b><small>day streak</small></div>
        <div class="stat card"><span>✅</span><b>${s.tasks}</b><small>quests done</small></div>
        <div class="stat card"><span>⭐</span><b>${s.earned}</b><small>stars earned</small></div>
      </div>
      <h3 class="section-title">🏆 Trophies</h3>
      <div class="badges">${BADGES.map(b => { const got = b.test(s); return `<div class="badge-item ${got ? 'got' : ''}" title="${esc(b.name)}"><span>${got ? b.icon : '🔒'}</span><small>${esc(b.name)}</small></div>`; }).join('')}</div>
      <h3 class="section-title">📜 Star history</h3>
      ${activityList(entries, false)}`;
  }

  /* ===================== Views: parent ===================== */
  function viewParent(tab) {
    const pend = pendingRedemptions().length;
    const titles = { kids: 'Kids', tasks: 'Tasks & schedule', rewards: 'Rewards', inbox: 'Inbox', settings: 'Settings' };
    const body = { kids: parentKids, tasks: parentTasks, rewards: parentRewards, inbox: parentInbox, settings: parentSettings }[tab]();
    const item = (id, icon, label, badge) => `<a class="nav-item ${tab === id ? 'active' : ''}" href="#/parent/${id}" ${tab === id ? 'aria-current="page"' : ''}><span>${icon}${badge ? `<b class="badge">${badge}</b>` : ''}</span><small>${label}</small></a>`;
    return `<div class="screen parent">
      <header class="parent-head">
        <button type="button" class="icon-btn" data-action="home" aria-label="Back to kids">
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <div class="grow"><p class="eyebrow">Parent zone</p><h1>${titles[tab]}</h1></div>
        <button type="button" class="btn btn-soft btn-small" data-action="lock">🔒 Lock</button>
      </header>
      ${!state.settings.pin ? `<div class="banner warn"><span>🔑</span><p>Set a parent PIN so kids can't change tasks or points.</p><button type="button" class="btn btn-primary btn-small" data-action="set-pin">Set PIN</button></div>` : ''}
      <div class="parent-body">${body}${appFooter()}</div>
      <nav class="bottom-nav five">
        ${item('kids', '👧', 'Kids')}${item('tasks', '🗓️', 'Tasks')}${item('rewards', '🎁', 'Rewards')}${item('inbox', '📬', 'Inbox', pend)}${item('settings', '⚙️', 'Settings')}
      </nav>
    </div>`;
  }

  function parentKids() {
    const k = todayKey();
    const rows = state.children.map(c => {
      const p = dayProgress(c.id, k);
      const taskCount = state.tasks.filter(t => t.childIds.includes(c.id)).length;
      const rewardCount = state.rewards.filter(r => r.childIds.includes(c.id)).length;
      return `<div class="card kid-row" style="--accent:${c.color}">
        <button type="button" class="kid-open" data-action="open-child" data-id="${c.id}" aria-label="Open ${esc(c.name)}'s quests">
          <span class="avatar avatar-md">${c.avatar}</span>
          <span class="grow"><b>${esc(c.name)}</b><small>${plural(taskCount, 'task')} · ${plural(rewardCount, 'reward')}</small>
            <span class="mini-bar"><i style="width:${p.total ? (p.done / p.total) * 100 : 0}%"></i></span>
            <small>Today: ${p.done}/${p.total} done</small></span>
        </button>
        <div class="kid-side">
          <span class="chip chip-star">⭐ ${balance(c.id)}</span>
          <div class="row-actions">
            <button type="button" class="icon-btn small" data-action="give-points" data-id="${c.id}" aria-label="Give or take stars">±</button>
            <button type="button" class="icon-btn small" data-action="edit-child" data-id="${c.id}" aria-label="Edit ${esc(c.name)}">✏️</button>
          </div>
        </div>
      </div>`;
    }).join('');
    return `<div class="stack-sm">${rows}</div>
      <button type="button" class="add-card" data-action="edit-child">＋ Add a child</button>
      ${state.children.length ? `<p class="hint">Tip: tap a child to see their quest screen. Use <b>±</b> to give bonus stars or take some away.</p>` : ''}`;
  }

  function parentTasks() {
    const f = ui.taskFilter;
    const list = state.tasks.filter(t => f === 'all' || t.childIds.includes(f)).sort(byTaskOrder);
    const chips = [`<button type="button" class="chip ${f === 'all' ? 'chip-on' : ''}" data-action="task-filter" data-id="all">Everyone</button>`]
      .concat(state.children.map(c => `<button type="button" class="chip ${f === c.id ? 'chip-on' : ''}" style="--accent:${c.color}" data-action="task-filter" data-id="${c.id}">${c.avatar} ${esc(c.name)}</button>`)).join('');
    const groups = SLOTS.map(sl => {
      const ts = list.filter(t => t.slot === sl.id);
      if (!ts.length) return '';
      return `<h3 class="section-title">${sl.icon} ${sl.label}</h3><div class="stack-sm">${ts.map(t => `
        <button type="button" class="card list-row" data-action="edit-task" data-id="${t.id}">
          <span class="row-icon">${t.icon}</span>
          <span class="grow"><b>${esc(t.title)}</b><small>${t.time ? `${fmtTime(t.time)} · ` : ''}${daysSummary(t.days)}</small></span>
          <span class="row-end"><span class="pts">+${t.points}⭐</span><span class="avatars">${avatarDots(t.childIds)}</span></span>
        </button>`).join('')}</div>`;
    }).join('');
    return `${state.children.length > 1 ? `<div class="chips">${chips}</div>` : ''}
      <button type="button" class="add-card" data-action="edit-task">＋ New task</button>
      ${list.length ? groups : `<div class="empty small"><div class="empty-art">🗓️</div><p>No tasks yet. Add routines like “Brush teeth” or “Homework”.</p></div>`}`;
  }

  function parentRewards() {
    const list = [...state.rewards].sort((a, b) => a.cost - b.cost);
    return `<button type="button" class="add-card" data-action="edit-reward">＋ New reward</button>
      ${list.length ? `<div class="stack-sm">${list.map(r => `
        <button type="button" class="card list-row" data-action="edit-reward" data-id="${r.id}">
          <span class="row-icon">${r.icon}</span>
          <span class="grow"><b>${esc(r.title)}</b><small>${r.childIds.length ? `For ${r.childIds.map(getChild).filter(Boolean).map(c => esc(c.name)).join(', ')}` : 'Not assigned to anyone'}</small></span>
          <span class="row-end"><span class="pts">⭐ ${r.cost}</span><span class="avatars">${avatarDots(r.childIds)}</span></span>
        </button>`).join('')}</div>`
      : `<div class="empty small"><div class="empty-art">🎁</div><p>No rewards yet. Add treats your kids can buy with their stars.</p></div>`}`;
  }

  function parentInbox() {
    const pend = pendingRedemptions().sort((a, b) => a.at - b.at);
    const recent = [...state.ledger].sort((a, b) => b.at - a.at).slice(0, 30);
    return `<h3 class="section-title">🎁 Reward requests</h3>
      ${pend.length ? `<div class="stack-sm">${pend.map(r => { const c = getChild(r.childId); return `
        <div class="card request" style="--accent:${c ? c.color : '#FF7A59'}">
          <span class="row-icon">${r.icon}</span>
          <div class="grow"><b>${esc(r.title)}</b><small>${c ? `${c.avatar} ${esc(c.name)}` : 'Unknown'} · ⭐ ${r.cost} · ${fmtWhen(r.at)}</small></div>
          <div class="row-actions">
            <button type="button" class="icon-btn small no" data-action="resolve" data-id="${r.id}" data-ok="0" aria-label="Decline">✕</button>
            <button type="button" class="icon-btn small yes" data-action="resolve" data-id="${r.id}" data-ok="1" aria-label="Approve">✓</button>
          </div>
        </div>`; }).join('')}</div>`
      : `<div class="empty small"><div class="empty-art">📭</div><p>All caught up! No requests waiting.</p></div>`}
      <h3 class="section-title">📜 Recent activity</h3>
      ${activityList(recent, true)}`;
  }

  function parentSettings() {
    const st = state.settings;
    const toggle = (key, label, sub) => `<label class="setting"><span class="grow"><b>${label}</b><small>${sub}</small></span><input type="checkbox" class="switch" data-setting="${key}" ${st[key] ? 'checked' : ''}></label>`;
    return `<div class="card settings">
        <div class="setting"><span class="grow"><b>Parent PIN</b><small>${st.pin ? 'Kids need the PIN to open this area' : 'No PIN set – anyone can open this area'}</small></span>
          <button type="button" class="btn btn-soft btn-small" data-action="set-pin">${st.pin ? 'Change' : 'Set PIN'}</button></div>
        ${st.pin ? `<div class="setting"><span class="grow"><b>Remove PIN</b><small>Open the parent zone without a PIN</small></span><button type="button" class="btn btn-soft btn-small" data-action="remove-pin">Remove</button></div>` : ''}
        ${toggle('requireApproval', 'Approve rewards', 'Rewards wait for your OK before they are given')}
        ${toggle('sound', 'Sounds', 'Happy chimes when quests are completed')}
      </div>
      <h3 class="section-title">💾 Backup</h3>
      <div class="card settings">
        <div class="setting"><span class="grow"><b>Export data</b><small>Save everything to a file</small></span><button type="button" class="btn btn-soft btn-small" data-action="export">Export</button></div>
        <div class="setting"><span class="grow"><b>Import data</b><small>Restore from a file or pasted text</small></span><button type="button" class="btn btn-soft btn-small" data-action="import">Import</button></div>
      </div>
      <h3 class="section-title">🧹 Danger zone</h3>
      <div class="card settings">
        <div class="setting"><span class="grow"><b>Load example data</b><small>Replaces everything with a demo family</small></span><button type="button" class="btn btn-soft btn-small" data-action="load-sample">Load</button></div>
        <div class="setting"><span class="grow"><b>Reset everything</b><small>Delete all kids, tasks, rewards and stars</small></span><button type="button" class="btn btn-danger btn-small" data-action="reset">Reset</button></div>
      </div>
      <h3 class="section-title">ℹ️ About</h3>
      <div class="card settings">
        <div class="setting"><span class="grow"><b>Version</b><small>KidQuest web app</small></span><span class="chip">v${APP_VERSION}</span></div>
      </div>
      <p class="hint center">KidQuest stores data only on this device. Export a backup to move it to another phone.</p>`;
  }

  /* ===================== Bottom sheet ===================== */
  const sheetRoot = document.getElementById('sheet-root');
  let sheetToken = 0;
  let lastFocus = null;
  function openSheet(html) {
    const t = ++sheetToken;
    lastFocus = document.activeElement;
    sheetRoot.innerHTML = `<div class="sheet-backdrop" data-action="close-sheet"></div>
      <div class="sheet" role="dialog" aria-modal="true"><div class="sheet-grip" aria-hidden="true"></div>
        <button type="button" class="sheet-close icon-btn small" data-action="close-sheet" aria-label="Close">✕</button>${html}</div>`;
    sheetRoot.classList.add('open');
    document.body.classList.add('no-scroll');
    requestAnimationFrame(() => requestAnimationFrame(() => { if (t === sheetToken) sheetRoot.classList.add('show'); }));
  }
  function closeSheet() {
    if (!sheetRoot.classList.contains('open')) return;
    const t = ++sheetToken;
    pinCtx = null;
    confirmCb = null;
    sheetRoot.classList.remove('show');
    document.body.classList.remove('no-scroll');
    setTimeout(() => { if (t === sheetToken) { sheetRoot.classList.remove('open'); sheetRoot.innerHTML = ''; } }, 260);
    if (lastFocus && document.contains(lastFocus)) try { lastFocus.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
  }

  let confirmCb = null;
  function confirmSheet({ icon = '🤔', title, text = '', ok = 'Yes', danger = false, onOk }) {
    openSheet(`<div class="confirm"><div class="confirm-icon">${icon}</div><h2 class="sheet-title">${title}</h2>${text ? `<p class="muted">${text}</p>` : ''}
      <div class="sheet-actions"><button type="button" class="btn btn-ghost grow" data-action="close-sheet">Cancel</button>
      <button type="button" class="btn ${danger ? 'btn-danger' : 'btn-primary'} grow" data-action="confirm-ok">${ok}</button></div></div>`);
    confirmCb = onOk;
  }

  /* ----- PIN ----- */
  let pinCtx = null;
  const PIN_TITLES = { unlock: 'Enter parent PIN', set: 'Choose a 4-digit PIN', confirm: 'Type it again to confirm' };
  function openPin(mode, onSuccess) {
    openSheet(`<div class="pin"><div class="pin-lock">🔐</div><h2 class="sheet-title" id="pin-title"></h2><p class="pin-msg" id="pin-msg"></p>
      <div class="pin-dots" id="pin-dots"></div>
      <div class="keypad">${[1, 2, 3, 4, 5, 6, 7, 8, 9, '', 0, '⌫'].map(k => k === '' ? '<span></span>' : `<button type="button" class="key" data-action="pin-key" data-key="${k}" aria-label="${k === '⌫' ? 'Delete' : k}">${k}</button>`).join('')}</div></div>`);
    pinCtx = { mode, buf: '', first: '', onSuccess };
    refreshPin('');
  }
  function refreshPin(msg) {
    if (!pinCtx) return;
    const t = $('#pin-title'), d = $('#pin-dots'), m = $('#pin-msg');
    if (t) t.textContent = PIN_TITLES[pinCtx.mode];
    if (m) m.textContent = msg !== undefined ? msg : (m.textContent || '');
    if (d) d.innerHTML = [0, 1, 2, 3].map(i => `<i class="${i < pinCtx.buf.length ? 'on' : ''}"></i>`).join('');
  }
  let pinTimer;
  function pinKey(k) {
    if (!pinCtx) return;
    if (pinCtx.buf.length === 4) {
      // A key arrived before the short "show the 4th dot" delay ended: submit now, then apply it.
      clearTimeout(pinTimer);
      pinSubmit();
      if (!pinCtx || k === '⌫') return;
    }
    if (k === '⌫') pinCtx.buf = pinCtx.buf.slice(0, -1);
    else pinCtx.buf += k;
    refreshPin();
    if (pinCtx.buf.length === 4) pinTimer = setTimeout(pinSubmit, 140);
  }
  function pinSubmit() {
    const c = pinCtx;
    if (!c || c.buf.length !== 4) return;
    if (c.mode === 'unlock') {
      if (c.buf === state.settings.pin) { const cb = c.onSuccess; closeSheet(); cb && cb(); }
      else pinFail('Oops, wrong PIN. Try again.');
    } else if (c.mode === 'set') {
      c.first = c.buf; c.buf = ''; c.mode = 'confirm'; refreshPin('');
    } else if (c.first === c.buf) {
      state.settings.pin = c.buf; save();
      const cb = c.onSuccess; closeSheet(); toast('PIN saved 🔐'); cb && cb();
    } else {
      c.mode = 'set'; c.first = ''; pinFail("PINs didn't match. Start again.");
    }
  }
  function pinFail(msg) {
    if (!pinCtx) return;
    pinCtx.buf = '';
    const el = $('.pin');
    if (el) { el.classList.remove('shake'); void el.offsetWidth; el.classList.add('shake'); }
    vibrate(90);
    refreshPin(msg);
  }
  function enterParent(tab = 'kids') {
    if (ui.unlocked || !state.settings.pin) { ui.unlocked = true; sessionSet(true); go(`parent/${tab}`); return; }
    openPin('unlock', () => { ui.unlocked = true; sessionSet(true); go(`parent/${tab}`); });
  }

  /* ----- Forms ----- */
  const radioPicker = (name, items, current, cls = 'pick-grid') => `<div class="${cls}">${(current && !items.includes(current) ? [current, ...items] : items).map(a => `<label class="pick"><input type="radio" name="${name}" value="${a}" ${a === current ? 'checked' : ''}><span>${a}</span></label>`).join('')}</div>`;
  const kidPicker = selected => state.children.length
    ? `<div class="kid-picks">${state.children.map(c => `<label class="kid-pick" style="--accent:${c.color}"><input type="checkbox" name="kids" value="${c.id}" ${selected.includes(c.id) ? 'checked' : ''}><span><i>${c.avatar}</i>${esc(c.name)}</span></label>`).join('')}</div>`
    : `<p class="hint">Add a child first to assign this.</p>`;
  const stepper = (name, value, min, max, step) => `<div class="stepper" data-step="${step}"><button type="button" data-action="step" data-delta="-1" aria-label="Less">−</button><input type="number" name="${name}" value="${value}" min="${min}" max="${max}" inputmode="numeric" required><button type="button" data-action="step" data-delta="1" aria-label="More">+</button></div>`;

  function childForm(c) {
    const isNew = !c;
    const n = state.children.length;
    c = c || { name: '', avatar: AVATARS[(n * 3) % AVATARS.length], color: COLORS[(n * 3 + 4) % COLORS.length] };
    openSheet(`<form class="form" data-form="child" data-id="${isNew ? '' : c.id}" autocomplete="off">
      <h2 class="sheet-title">${isNew ? 'Add a child' : 'Edit profile'}</h2>
      <div class="preview-avatar" id="child-preview" style="--accent:${c.color}">${c.avatar}</div>
      <label class="field"><span>Name</span><input name="name" required maxlength="20" value="${esc(c.name)}" placeholder="e.g. Mia"></label>
      <fieldset class="field"><legend>Avatar</legend>${radioPicker('avatar', AVATARS, c.avatar)}</fieldset>
      <fieldset class="field"><legend>Colour</legend><div class="pick-row">${COLORS.map(col => `<label class="pick swatch" style="--sw:${col}"><input type="radio" name="color" value="${col}" ${col === c.color ? 'checked' : ''} aria-label="Colour ${col}"><span></span></label>`).join('')}</div></fieldset>
      <div class="sheet-actions">${isNew ? '' : `<button type="button" class="btn btn-danger-ghost" data-action="delete-child" data-id="${c.id}">Delete</button>`}
        <button class="btn btn-primary grow">${isNew ? 'Add child' : 'Save'}</button></div>
    </form>`);
  }

  function taskForm(t) {
    const isNew = !t;
    t = t || { title: '', icon: TASK_ICONS[0], points: 3, slot: currentSlot(), time: '', days: [0, 1, 2, 3, 4, 5, 6], childIds: ui.taskFilter !== 'all' ? [ui.taskFilter] : state.children.map(c => c.id) };
    openSheet(`<form class="form" data-form="task" data-id="${isNew ? '' : t.id}" autocomplete="off">
      <h2 class="sheet-title">${isNew ? 'New task' : 'Edit task'}</h2>
      ${isNew ? `<div class="field"><span>Quick ideas</span><div class="suggest">${TASK_SUGGESTIONS.map((s, i) => `<button type="button" class="chip" data-action="suggest-task" data-i="${i}">${s[1]} ${s[0]}</button>`).join('')}</div></div>` : ''}
      <label class="field"><span>Task name</span><input name="title" required maxlength="40" value="${esc(t.title)}" placeholder="e.g. Brush teeth"></label>
      <fieldset class="field"><legend>Icon</legend>${radioPicker('icon', TASK_ICONS, t.icon, 'pick-grid scroll')}</fieldset>
      <div class="field-row">
        <div class="field"><span>Stars earned</span>${stepper('points', t.points, 1, 999, 1)}</div>
        <label class="field"><span>Time (optional)</span><input type="time" name="time" value="${esc(t.time)}"></label>
      </div>
      <fieldset class="field"><legend>Part of day</legend><div class="segmented">${SLOTS.map(s => `<label><input type="radio" name="slot" value="${s.id}" ${s.id === t.slot ? 'checked' : ''}><span>${s.icon} ${s.label}</span></label>`).join('')}</div></fieldset>
      <fieldset class="field"><legend>Repeat on</legend>
        <div class="day-picks">${WEEK_ORDER.map(d => `<label><input type="checkbox" name="days" value="${d}" ${t.days.includes(d) ? 'checked' : ''}><span>${DAY_SHORT[d].slice(0, 2)}</span></label>`).join('')}</div>
        <div class="presets"><button type="button" class="chip" data-action="days-preset" data-p="all">Every day</button><button type="button" class="chip" data-action="days-preset" data-p="wk">Weekdays</button><button type="button" class="chip" data-action="days-preset" data-p="we">Weekends</button></div>
      </fieldset>
      <fieldset class="field"><legend>Who does it?</legend>${kidPicker(t.childIds)}</fieldset>
      <div class="sheet-actions">${isNew ? '' : `<button type="button" class="btn btn-danger-ghost" data-action="delete-task" data-id="${t.id}">Delete</button>`}
        <button class="btn btn-primary grow">${isNew ? 'Add task' : 'Save'}</button></div>
    </form>`);
  }

  function rewardForm(r) {
    const isNew = !r;
    r = r || { title: '', icon: REWARD_ICONS[0], cost: 50, childIds: state.children.map(c => c.id) };
    openSheet(`<form class="form" data-form="reward" data-id="${isNew ? '' : r.id}" autocomplete="off">
      <h2 class="sheet-title">${isNew ? 'New reward' : 'Edit reward'}</h2>
      ${isNew ? `<div class="field"><span>Quick ideas</span><div class="suggest">${REWARD_SUGGESTIONS.map((s, i) => `<button type="button" class="chip" data-action="suggest-reward" data-i="${i}">${s[1]} ${s[0]}</button>`).join('')}</div></div>` : ''}
      <label class="field"><span>Reward name</span><input name="title" required maxlength="40" value="${esc(r.title)}" placeholder="e.g. Ice cream treat"></label>
      <fieldset class="field"><legend>Icon</legend>${radioPicker('icon', REWARD_ICONS, r.icon, 'pick-grid scroll')}</fieldset>
      <div class="field"><span>Cost in stars</span>${stepper('cost', r.cost, 1, 9999, 5)}</div>
      <fieldset class="field"><legend>Available to</legend>${kidPicker(r.childIds)}</fieldset>
      <div class="sheet-actions">${isNew ? '' : `<button type="button" class="btn btn-danger-ghost" data-action="delete-reward" data-id="${r.id}">Delete</button>`}
        <button class="btn btn-primary grow">${isNew ? 'Add reward' : 'Save'}</button></div>
    </form>`);
  }

  function pointsForm(c) {
    openSheet(`<form class="form" data-form="points" data-id="${c.id}" autocomplete="off" style="--accent:${c.color}">
      <div class="preview-avatar small">${c.avatar}</div>
      <h2 class="sheet-title center">Stars for ${esc(c.name)}</h2>
      <p class="muted center">Current balance: <b>⭐ ${balance(c.id)}</b></p>
      <div class="field"><span>How many stars?</span>${stepper('amount', 5, 1, 999, 1)}
        <div class="presets">${[1, 5, 10, 20].map(n => `<button type="button" class="chip" data-action="set-amount" data-n="${n}">${n}</button>`).join('')}</div></div>
      <label class="field"><span>Reason</span><input name="reason" maxlength="60" placeholder="e.g. Helped with the dishes"></label>
      <div class="sheet-actions"><button class="btn btn-ghost grow" name="mode" value="remove">➖ Take away</button><button class="btn btn-primary grow" name="mode" value="add">➕ Give stars</button></div>
    </form>`);
  }

  const forms = {
    child(f, fd) {
      const name = String(fd.get('name') || '').trim();
      if (!name) return toast('Please enter a name');
      const data = { name, avatar: fd.get('avatar') || AVATARS[0], color: fd.get('color') || COLORS[0] };
      const id = f.dataset.id;
      if (id) Object.assign(getChild(id), data);
      else {
        const c = { id: uid(), ...data };
        state.children.push(c);
        if (state.children.length === 1) { state.tasks.forEach(t => t.childIds.push(c.id)); state.rewards.forEach(r => r.childIds.push(c.id)); }
      }
      save(); closeSheet(); render();
      toast(id ? 'Profile saved' : `Welcome, ${name}! ${data.avatar}`);
    },
    task(f, fd) {
      const title = String(fd.get('title') || '').trim();
      const days = fd.getAll('days').map(Number);
      const childIds = fd.getAll('kids').map(String);
      if (!title) return toast('Give the task a name');
      if (!days.length) return toast('Pick at least one day');
      if (state.children.length && !childIds.length) return toast('Choose who does this task');
      const data = { title, icon: fd.get('icon') || '⭐', points: clamp(parseInt(fd.get('points'), 10) || 1, 1, 999), slot: fd.get('slot') || 'morning', time: fd.get('time') || '', days, childIds };
      const id = f.dataset.id;
      if (id) Object.assign(getTask(id), data);
      else state.tasks.push({ id: uid(), created: todayKey(), ...data });
      save(); closeSheet(); render();
      toast(id ? 'Task saved' : 'Task added ✅');
    },
    reward(f, fd) {
      const title = String(fd.get('title') || '').trim();
      const childIds = fd.getAll('kids').map(String);
      if (!title) return toast('Give the reward a name');
      if (state.children.length && !childIds.length) return toast('Choose who can get this reward');
      const data = { title, icon: fd.get('icon') || '🎁', cost: clamp(parseInt(fd.get('cost'), 10) || 1, 1, 9999), childIds };
      const id = f.dataset.id;
      if (id) Object.assign(getReward(id), data);
      else state.rewards.push({ id: uid(), ...data });
      save(); closeSheet(); render();
      toast(id ? 'Reward saved' : 'Reward added 🎁');
    },
    points(f, fd, submitter) {
      const c = getChild(f.dataset.id);
      if (!c) return closeSheet();
      const n = clamp(parseInt(fd.get('amount'), 10) || 0, 1, 999);
      const remove = submitter && submitter.value === 'remove';
      const reason = String(fd.get('reason') || '').trim() || (remove ? 'Stars taken away' : 'Bonus stars');
      adjustPoints(c.id, remove ? -n : n, `${remove ? '⚠️' : '🌟'} ${reason}`);
      closeSheet(); render();
      toast(remove ? `Took ${n} ⭐ from ${c.name}` : `Gave ${n} ⭐ to ${c.name}!`);
      if (!remove) { confetti(window.innerWidth / 2, window.innerHeight / 2, 30); ding([784, 988, 1175]); }
    },
  };

  /* ===================== Effects ===================== */
  const toastEl = document.getElementById('toast');
  let toastTimer;
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('show'), 2400);
  }
  const fx = document.getElementById('fx');
  function confetti(x, y, n = 24) {
    if (reducedMotion()) return;
    for (let i = 0; i < n; i++) {
      const p = document.createElement('i');
      p.className = 'confetti' + (i % 3 === 0 ? ' round' : '');
      const ang = Math.random() * Math.PI * 2, dist = 50 + Math.random() * 130;
      p.style.left = `${x}px`; p.style.top = `${y}px`;
      p.style.setProperty('--dx', `${Math.cos(ang) * dist}px`);
      p.style.setProperty('--dy', `${Math.sin(ang) * dist - 70}px`);
      p.style.setProperty('--r', `${Math.random() * 720 - 360}deg`);
      p.style.background = CONFETTI_COLORS[i % CONFETTI_COLORS.length];
      fx.appendChild(p);
      setTimeout(() => p.remove(), 1300);
    }
  }
  function floatText(x, y, text) {
    const el = document.createElement('div');
    el.className = 'float-text';
    el.textContent = text;
    el.style.left = `${x}px`; el.style.top = `${y}px`;
    fx.appendChild(el);
    setTimeout(() => el.remove(), 1100);
  }
  let actx;
  function ding(freqs = [660, 880]) {
    if (!state.settings.sound) return;
    try {
      actx = actx || new (window.AudioContext || window.webkitAudioContext)();
      if (actx.state === 'suspended') actx.resume();
      const t0 = actx.currentTime;
      freqs.forEach((f, i) => {
        const o = actx.createOscillator(), g = actx.createGain(), t = t0 + i * 0.09;
        o.type = 'triangle'; o.frequency.value = f;
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(0.18, t + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.3);
        o.connect(g).connect(actx.destination);
        o.start(t); o.stop(t + 0.32);
      });
    } catch (e) { /* audio unavailable */ }
  }
  function celebrate(c) {
    const el = document.createElement('div');
    el.className = 'celebrate';
    el.style.setProperty('--accent', c.color);
    el.innerHTML = `<div class="celebrate-card" role="alertdialog" aria-label="All quests done"><div class="celebrate-trophy">🏆</div><h2>Amazing, ${esc(c.name)}!</h2><p>You finished all of today's quests.</p><button type="button" class="btn btn-primary btn-lg">Yay! 🎉</button></div>`;
    const close = () => { el.classList.remove('show'); setTimeout(() => el.remove(), 300); };
    el.addEventListener('click', close);
    document.body.appendChild(el);
    requestAnimationFrame(() => el.classList.add('show'));
    ding([523, 659, 784, 1047]);
    vibrate([30, 60, 30]);
    const w = window.innerWidth, h = window.innerHeight;
    [[0.2, 0.3], [0.8, 0.3], [0.5, 0.2], [0.3, 0.6], [0.7, 0.6]].forEach(([fxp, fyp], i) => setTimeout(() => confetti(w * fxp, h * fyp, 26), i * 140));
    setTimeout(close, 5000);
  }

  /* ===================== Actions ===================== */
  const actions = {
    'start-sample'() { state = sampleState(); save(); go(''); toast('Meet Mia and Leo! 👋'); },
    'start-fresh'() { state.onboarded = true; save(); ui.unlocked = true; sessionSet(true); go('parent/kids'); setTimeout(() => childForm(), 300); },
    home() { go(''); },
    parent() { enterParent('kids'); },
    lock() { ui.unlocked = false; sessionSet(false); go(''); toast('Parent zone locked 🔒'); },
    'open-child'(el) { ui.date = todayKey(); go(`child/${el.dataset.id}/today`); window.scrollTo(0, 0); },
    'pick-day'(el) { ui.date = el.dataset.day; render(); },
    week(el) { ui.date = addDays(ui.date, 7 * Number(el.dataset.dir)); render(); },
    'toggle-task'(el) {
      const cid = route()[1], tid = el.dataset.id;
      const rect = el.getBoundingClientRect();
      const done = toggleTask(cid, tid, ui.date);
      render();
      const fresh = $(`[data-action="toggle-task"][data-id="${tid}"]`);
      if (!done) { vibrate(10); return; }
      const t = getTask(tid);
      fresh && fresh.classList.add('pop');
      const y = rect.top + rect.height / 2;
      floatText(rect.right - 70, y - 10, `+${t.points} ⭐`);
      confetti(rect.right - 32, y, 16);
      ding();
      vibrate(20);
      const b = $('#balance');
      if (b) { b.classList.remove('bump'); void b.offsetWidth; b.classList.add('bump'); }
      if (dayComplete(cid, ui.date)) setTimeout(() => celebrate(getChild(cid)), 500);
    },
    redeem(el) {
      const cid = route()[1], r = getReward(el.dataset.id);
      if (!r) return;
      confirmSheet({
        icon: r.icon, title: `Get “${esc(r.title)}”?`,
        text: `This will use <b>⭐ ${r.cost}</b> of your ${balance(cid)} stars.${state.settings.requireApproval ? ' A grown-up will need to say OK.' : ''}`,
        ok: 'Yes, get it!',
        onOk() {
          const status = redeem(cid, r.id);
          if (!status) return toast('Not enough stars yet');
          render();
          confetti(window.innerWidth / 2, window.innerHeight / 3, 36);
          ding([659, 784, 1047]);
          toast(status === 'pending' ? 'Sent to a grown-up for approval ⏳' : `Enjoy your ${r.title}! 🎉`);
        },
      });
    },
    'close-sheet'() { closeSheet(); },
    'confirm-ok'() { const cb = confirmCb; closeSheet(); cb && cb(); },
    'pin-key'(el) { pinKey(el.dataset.key); },
    'set-pin'() { openPin('set', () => render()); },
    'remove-pin'() { confirmSheet({ icon: '🔓', title: 'Remove the PIN?', text: 'Anyone will be able to open the parent zone.', ok: 'Remove', danger: true, onOk() { state.settings.pin = ''; save(); render(); toast('PIN removed'); } }); },
    'edit-child'(el) { childForm(el.dataset.id ? getChild(el.dataset.id) : null); },
    'delete-child'(el) {
      const c = getChild(el.dataset.id);
      if (!c) return;
      confirmSheet({
        icon: '😢', title: `Delete ${esc(c.name)}?`, text: 'Their stars, history and reward requests will be removed. Tasks and rewards stay for the other kids.', ok: 'Delete', danger: true,
        onOk() {
          state.children = state.children.filter(x => x.id !== c.id);
          state.tasks.forEach(t => { t.childIds = t.childIds.filter(i => i !== c.id); });
          state.rewards.forEach(r => { r.childIds = r.childIds.filter(i => i !== c.id); });
          state.ledger = state.ledger.filter(e => e.childId !== c.id);
          state.redemptions = state.redemptions.filter(r => r.childId !== c.id);
          Object.values(state.completions).forEach(day => { delete day[c.id]; });
          if (ui.taskFilter === c.id) ui.taskFilter = 'all';
          save(); render(); toast(`${c.name} removed`);
        },
      });
    },
    'give-points'(el) { const c = getChild(el.dataset.id); if (c) pointsForm(c); },
    'set-amount'(el) { const i = el.closest('form').querySelector('[name="amount"]'); if (i) i.value = el.dataset.n; },
    'edit-task'(el) { taskForm(el.dataset.id ? getTask(el.dataset.id) : null); },
    'delete-task'(el) {
      const t = getTask(el.dataset.id);
      if (!t) return;
      confirmSheet({ icon: t.icon, title: `Delete “${esc(t.title)}”?`, text: 'Stars already earned for it are kept.', ok: 'Delete', danger: true, onOk() { state.tasks = state.tasks.filter(x => x.id !== t.id); save(); render(); toast('Task deleted'); } });
    },
    'task-filter'(el) { ui.taskFilter = el.dataset.id; render(); },
    'edit-reward'(el) { rewardForm(el.dataset.id ? getReward(el.dataset.id) : null); },
    'delete-reward'(el) {
      const r = getReward(el.dataset.id);
      if (!r) return;
      confirmSheet({ icon: r.icon, title: `Delete “${esc(r.title)}”?`, ok: 'Delete', danger: true, onOk() { state.rewards = state.rewards.filter(x => x.id !== r.id); save(); render(); toast('Reward deleted'); } });
    },
    'suggest-task'(el) {
      const [title, icon, slot] = TASK_SUGGESTIONS[Number(el.dataset.i)];
      const f = el.closest('form');
      f.querySelector('[name="title"]').value = title;
      const ic = f.querySelector(`input[name="icon"][value="${icon}"]`); if (ic) { ic.checked = true; ic.closest('label').scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' }); }
      const sl = f.querySelector(`input[name="slot"][value="${slot}"]`); if (sl) sl.checked = true;
    },
    'suggest-reward'(el) {
      const [title, icon, cost] = REWARD_SUGGESTIONS[Number(el.dataset.i)];
      const f = el.closest('form');
      f.querySelector('[name="title"]').value = title;
      f.querySelector('[name="cost"]').value = cost;
      const ic = f.querySelector(`input[name="icon"][value="${icon}"]`); if (ic) { ic.checked = true; ic.closest('label').scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' }); }
    },
    'days-preset'(el) {
      const set = { all: [0, 1, 2, 3, 4, 5, 6], wk: [1, 2, 3, 4, 5], we: [0, 6] }[el.dataset.p];
      el.closest('form').querySelectorAll('input[name="days"]').forEach(i => { i.checked = set.includes(Number(i.value)); });
    },
    step(el) {
      const wrap = el.closest('.stepper'), input = wrap.querySelector('input');
      const step = Number(wrap.dataset.step) || 1;
      const v = (parseInt(input.value, 10) || 0) + Number(el.dataset.delta) * step;
      input.value = clamp(v, Number(input.min) || 1, Number(input.max) || 9999);
    },
    resolve(el) {
      const ok = el.dataset.ok === '1';
      const r = state.redemptions.find(x => x.id === el.dataset.id);
      resolveRedemption(el.dataset.id, ok);
      render();
      if (r) toast(ok ? `Approved: ${r.icon} ${r.title}` : `Declined – stars refunded`);
      if (ok) ding([659, 880]);
    },
    export() {
      const blob = new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `kidquest-backup-${todayKey()}.json`;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 2000);
      toast('Backup downloaded 💾');
    },
    'load-sample'() { confirmSheet({ icon: '✨', title: 'Load example data?', text: 'This replaces all your current kids, tasks, rewards and stars.', ok: 'Load examples', danger: true, onOk() { const pin = state.settings.pin; state = sampleState(); state.settings.pin = pin; save(); render(); toast('Example family loaded'); } }); },
    reset() {
      confirmSheet({
        icon: '🧹', title: 'Reset everything?', text: 'All kids, tasks, rewards, stars and settings will be deleted. This cannot be undone.', ok: 'Delete everything', danger: true,
        onOk() { state = defaultState(); save(); ui.unlocked = false; sessionSet(false); ui.taskFilter = 'all'; go(''); },
      });
    },
  };

  /* ===================== Event wiring ===================== */
  document.addEventListener('click', e => {
    const el = e.target.closest('[data-action]');
    if (!el || el.disabled) return;
    const fn = actions[el.dataset.action];
    if (fn) fn(el, e);
  });
  document.addEventListener('submit', e => {
    const f = e.target.closest('form[data-form]');
    if (!f) return;
    e.preventDefault();
    const fn = forms[f.dataset.form];
    if (fn) fn(f, new FormData(f), e.submitter);
  });
  document.addEventListener('change', e => {
    const t = e.target;
    if (t.matches('[data-setting]')) {
      state.settings[t.dataset.setting] = t.checked; save();
      toast(`${t.closest('.setting').querySelector('b').textContent}: ${t.checked ? 'on' : 'off'}`);
      return;
    }
    const form = t.closest('form[data-form="child"]');
    if (form) {
      const pv = $('#child-preview');
      if (pv && t.name === 'avatar') { pv.textContent = t.value; pv.classList.remove('pop'); void pv.offsetWidth; pv.classList.add('pop'); }
      if (pv && t.name === 'color') pv.style.setProperty('--accent', t.value);
    }
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && sheetRoot.classList.contains('open')) { closeSheet(); return; }
    if (pinCtx && /^[0-9]$/.test(e.key)) pinKey(e.key);
    else if (pinCtx && e.key === 'Backspace') pinKey('⌫');
  });
  window.addEventListener('hashchange', () => { closeSheet(); render(); window.scrollTo(0, 0); });
  // Only redraw on return when the day rolled over. Redrawing unconditionally would also run when
  // Android's file picker hands control back, which used to swap out the page mid-import.
  let shownDay = todayKey();
  document.addEventListener('visibilitychange', () => {
    if (document.hidden || todayKey() === shownDay) return;
    shownDay = todayKey();
    if (ui.date < shownDay) ui.date = shownDay;
    if (!sheetRoot.classList.contains('open')) render();
  });

  /* ----- Backup import ----- */
  // Shows the problem in a sheet (not a toast) so it can't be missed or hidden.
  function importError(msg, detail) {
    openSheet(`<div class="confirm"><div class="confirm-icon">😕</div><h2 class="sheet-title">Couldn't import</h2>
      <p class="muted">${esc(msg)}</p>${detail ? `<pre class="import-detail">${esc(detail)}</pre>` : ''}
      <div class="sheet-actions"><button type="button" class="btn btn-ghost grow" data-action="close-sheet">Close</button>
      <button type="button" class="btn btn-primary grow" data-action="import">Try again</button></div></div>`);
  }
  // Single entry point for file, paste and link imports.
  function offerRestore(text, source) {
    let data;
    const raw = String(text || '').replace(/^﻿/, '').trim();
    if (!raw) return importError(`${source} is empty.`);
    try { data = JSON.parse(raw); }
    catch (err) { return importError(`${source} isn't a KidQuest backup (not valid JSON). It starts with:`, raw.slice(0, 80)); }
    if (!data || !Array.isArray(data.children) || !Array.isArray(data.tasks)) return importError(`${source} is JSON, but has no kids or tasks in it.`);
    confirmSheet({
      icon: '📥', title: 'Restore this backup?', ok: 'Restore', danger: true,
      text: `It has ${plural(data.children.length, 'child', 'children')}, ${plural(data.tasks.length, 'task')} and ${plural((data.rewards || []).length, 'reward')}.${state.children.length ? ' Your current data will be replaced.' : ''}`,
      onOk() { state = normalize(data); state.onboarded = true; save(); ui.taskFilter = 'all'; render(); toast('Backup restored ✅'); },
    });
  }

  // One permanent file input that is never re-rendered (Android's picker hides the page).
  const importInput = document.createElement('input');
  importInput.type = 'file';
  importInput.className = 'visually-hidden'; // not display:none – some mobile browsers skip events on those
  importInput.tabIndex = -1;
  importInput.setAttribute('aria-hidden', 'true');
  document.body.appendChild(importInput);
  importInput.addEventListener('change', () => {
    const file = importInput.files && importInput.files[0];
    if (!file) { toast('No file chosen'); return; }
    toast(`Reading ${file.name}…`);
    const reader = new FileReader();
    reader.onerror = () => importError(`Your phone wouldn't let the app read “${file.name}”.`, String(reader.error || ''));
    reader.onload = () => { importInput.value = ''; offerRestore(reader.result, `“${file.name}”`); };
    reader.readAsText(file);
  });

  actions.import = () => {
    openSheet(`<div class="form"><h2 class="sheet-title">Import data</h2>
      <p class="muted">Restoring replaces everything currently in the app.</p>
      <button type="button" class="btn btn-primary btn-lg" data-action="import-file">📁 Choose a backup file</button>
      <div class="or"><span>or paste the backup text</span></div>
      <form class="form" data-form="paste"><textarea name="json" rows="5" placeholder='{"version": 1, "children": [ … ] }' spellcheck="false" autocapitalize="off"></textarea>
      <button class="btn btn-ghost">📋 Import pasted text</button></form></div>`);
  };
  actions['import-file'] = () => importInput.click();
  forms.paste = (f, fd) => offerRestore(fd.get('json'), 'The pasted text');

  // Link import: index.html?import=samples/family-sample.json (same-site files only).
  (function importFromLink() {
    const params = new URLSearchParams(location.search);
    const src = params.get('import');
    if (!src) return;
    history.replaceState(null, '', location.pathname + location.hash);
    let url;
    try { url = new URL(src, location.href); } catch (e) { return; }
    if (url.origin !== location.origin || !/\.json$/i.test(url.pathname)) return importError('That import link is not allowed.');
    fetch(url, { cache: 'no-cache' })
      .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.text(); })
      .then(text => offerRestore(text, `“${url.pathname.split('/').pop()}”`))
      .catch(err => importError('Could not download the backup from that link.', String(err)));
  })();

  render();

  if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost')) {
    window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
  }
})();
