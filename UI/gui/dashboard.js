// Dashboard: charts with time on the x axis, built only from /orders/ and /stations/ (nothing extra on the server).
// Uses the helpers of app.js (checkDuration, checkEnergy, checkContent, checkWorkflow, sortedLogs, STATION_VISUALS, ...).
// Only finished orders count as "production"; violations use the same checks as the badges in the orders table.

const DASH_WINDOWS = { all: Infinity, "5m": 5 * 60000, "1m": 60000 };
const DASH = { window: "all", sig: "" };

const VIOLATION_CATS = [
  { key: "any",      label: "Any",      color: "#eaeaea" },
  { key: "deadline", label: "Deadline", color: "#ff9d5c" },
  { key: "content",  label: "Content",  color: "#4a90d9" },
  { key: "energy",   label: "Energy",   color: "#e6c95c" },
  { key: "workflow", label: "Workflow", color: "#c58af9" },     // a station was skipped / visited twice
];
const UTIL_COLORS = { Trunks: "#ff9d5c", Wheels: "#4a90d9", Torque: "#7ee08a", Optional: "#c58af9" };

// ------------------------------------------------------------------------------------------ data
function dashRecords(orders, stations) {
  return orders.filter(o => o.finished_at && o.created_at).map(o => {
    const t = Date.parse(o.finished_at), created = Date.parse(o.created_at);
    const dur = checkDuration(o), en = checkEnergy(o);
    const v = { deadline: dur.violation, content: checkContent(o) === "bad", energy: en.violation, workflow: checkWorkflow(o, stations) === "bad" };
    v.any = v.deadline || v.content || v.energy || v.workflow;
    let waitMs = 0, passes = 0, faults = 0, fast = 0;
    for (const l of sortedLogs(o)) {
      const p = l.data_payload || {};
      if (l.new_status === "IN_PROGRESS" && typeof p.queue_wait_ms === "number") waitMs += p.queue_wait_ms;
      if (l.new_status === "COMPLETED") { passes++; if (p.fault_injected === true) faults++; if (p.speed === "fast") fast++; }
    }
    return { id: o.id, t, created, durMs: t - created, deadlineMs: dur.max, energy: en.used, budget: en.budget, waitMs, passes, faults, fast, v };
  }).sort((a, b) => a.t - b.t || a.id - b.id);
}

// busy time of each station type: from "Assembly started" to "Assembly completed" of every pass
function busyIntervals(orders) {
  const out = {}, now = Date.now();
  for (const o of orders) {
    const open = {};
    for (const l of sortedLogs(o)) {
      const key = l.station + "|" + ((l.data_payload && l.data_payload.instance) ?? "");
      const t = Date.parse(l.timestamp);
      if (l.new_status === "IN_PROGRESS") open[key] = t;
      else if (l.new_status === "COMPLETED" && open[key] != null) { (out[l.station] = out[l.station] || []).push([open[key], t]); delete open[key]; }
    }
    for (const key of Object.keys(open)) (out[key.split("|")[0]] = out[key.split("|")[0]] || []).push([open[key], now]);
  }
  return out;
}

function utilisationSeries(orders, stationsInfo, x0, x1) {
  const instances = {};
  for (const s of stationsInfo) instances[s.station] = (instances[s.station] || 0) + 1;
  const busy = busyIntervals(orders), win = Math.max(5000, (x1 - x0) / 12), n = 60;
  return TYPE_ORDER.filter(t => instances[t]).map(type => {
    const pts = [];
    for (let k = 0; k <= n; k++) {
      const t = x0 + ((x1 - x0) * k) / n, a = t - win;
      let sum = 0;
      for (const [s, e] of (busy[type] || [])) sum += Math.max(0, Math.min(e, t) - Math.max(s, a));
      pts.push([t, Math.min(100, (100 * sum) / (win * instances[type]))]);
    }
    return { name: type, color: UTIL_COLORS[type] || "#999", kind: "line", pts };
  });
}

const runningMean = (recs, f) => { let s = 0; return recs.map((r, i) => { s += f(r); return [r.t, s / (i + 1), r.id]; }); };
const dots = (recs, f) => recs.map(r => [r.t, f(r), r.id]);

// ------------------------------------------------------------------------------------------ drawing (plain SVG)
const pad2 = n => String(n).padStart(2, "0");
const fmtClock = t => { const d = new Date(t); return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`; };
const num1 = v => (Math.round(v * 10) / 10).toString();

function niceCeil(v) {
  if (!(v > 0)) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(v))), f = v / p;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
}

// keep the part of a series inside [x0, x1]; "extend" starts it at x0 with the value it had and carries the last value to x1
function clipPts(pts, x0, x1, extend) {
  let before = null; const inside = [];
  for (const p of pts) { if (p[0] < x0) before = p; else if (p[0] <= x1) inside.push(p); }
  if (!extend) return inside;
  const out = before ? [[x0, before[1]], ...inside] : inside;
  if (out.length) out.push([x1, out[out.length - 1][1]]);
  return out;
}

function chartSVG(cfg) {
  const W = 460, H = 238, L = 44, R = 12, T = 42, B = 26, iw = W - L - R, ih = H - T - B;
  const span = Math.max(1, cfg.x1 - cfg.x0);
  const sx = t => L + ((t - cfg.x0) / span) * iw;
  const series = cfg.series.map(s => ({ ...s, cp: clipPts(s.pts, cfg.x0, cfg.x1, s.kind !== "dots") }));
  const hasData = series.some(s => s.cp.length);
  let ymax = 0;
  for (const s of series) for (const p of s.cp) ymax = Math.max(ymax, p[1]);
  ymax = cfg.fixedMax ?? niceCeil(Math.max(ymax * 1.08, cfg.minMax ?? 1));
  const sy = v => T + ih - (Math.min(v, ymax) / ymax) * ih;
  const fmt = cfg.yFmt || num1;
  const txt = (x, y, s, anchor, extra = "") => `<text x="${x}" y="${y}" fill="#999" font-size="10" font-family="sans-serif" text-anchor="${anchor}" ${extra}>${s}</text>`;

  let g = "";
  for (let i = 0; i <= 4; i++) {                                   // horizontal grid + y labels
    const v = (ymax * i) / 4, y = sy(v).toFixed(1);
    g += `<line x1="${L}" x2="${W - R}" y1="${y}" y2="${y}" stroke="#333" stroke-width="1"/>` + txt(L - 5, +y + 3, fmt(v), "end");
  }
  for (let i = 0; i <= 4; i++) {                                   // x ticks: clock time
    const t = cfg.x0 + (span * i) / 4, x = sx(t).toFixed(1);
    g += `<line x1="${x}" x2="${x}" y1="${T + ih}" y2="${T + ih + 4}" stroke="#666"/>` + txt(x, H - 8, fmtClock(t), i === 0 ? "start" : i === 4 ? "end" : "middle");
  }
  g += `<line x1="${L}" x2="${L}" y1="${T}" y2="${T + ih}" stroke="#666"/><line x1="${L}" x2="${W - R}" y1="${T + ih}" y2="${T + ih}" stroke="#666"/>`;

  for (const s of series) {
    const tip = p => `${p[2] != null ? "order " + p[2] + " · " : ""}${fmtClock(p[0])} · ${s.name}: ${fmt(p[1])}`;
    if (s.kind === "dots") {
      g += s.cp.map(p => `<circle cx="${sx(p[0]).toFixed(1)}" cy="${sy(p[1]).toFixed(1)}" r="2.4" fill="${s.color}" opacity="0.55"><title>${tip(p)}</title></circle>`).join("");
    } else if (s.cp.length) {
      let d = "";
      s.cp.forEach((p, i) => {
        const x = sx(p[0]).toFixed(1), y = sy(p[1]).toFixed(1);
        if (i === 0) d = `M${x} ${y}`;
        else d += s.kind === "step" ? ` H${x} V${y}` : ` L${x} ${y}`;
      });
      g += `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="2" stroke-linejoin="round"${s.dash ? ' stroke-dasharray="5 4"' : ""}/>`;
      if (s.markers) g += s.cp.filter(p => p[0] < cfg.x1).map(p => `<circle cx="${sx(p[0]).toFixed(1)}" cy="${sy(p[1]).toFixed(1)}" r="2.2" fill="${s.color}"><title>${tip(p)}</title></circle>`).join("");
    }
  }
  if (!hasData) g += txt(L + iw / 2, T + ih / 2, "waiting for data…", "middle", 'font-style="italic"');

  let lx = L, ly = 12, legend = "";                                // legend: name + latest value, wraps to a second row
  for (const s of series) {
    const last = s.pts.length ? s.pts[s.pts.length - 1][1] : null;
    const label = `${s.name}${last != null ? " " + fmt(last) : ""}`, w = 16 + label.length * 5.6 + 10;
    if (lx + w > W - R) { lx = L; ly += 14; }
    legend += (s.kind === "dots" ? `<circle cx="${lx + 5}" cy="${ly - 3}" r="2.6" fill="${s.color}" opacity="0.7"/>`
                                 : `<line x1="${lx}" x2="${lx + 10}" y1="${ly - 3}" y2="${ly - 3}" stroke="${s.color}" stroke-width="2"${s.dash ? ' stroke-dasharray="3 2"' : ""}/>`)
            + txt(lx + 14, ly, label, "start", 'fill-opacity="1"').replace('fill="#999"', 'fill="#ccc"');
    lx += w;
  }
  return `<svg viewBox="0 0 ${W} ${H}" class="chart-svg" role="img" aria-label="${cfg.title}">${g}${legend}</svg>`;
}

function chartCard(cfg) {
  return `<div class="chart-card"><div class="chart-title">${cfg.title}</div>${chartSVG(cfg)}${cfg.note ? `<div class="chart-note">${cfg.note}</div>` : ""}</div>`;
}

function kpi(label, value, sub, color) {
  return `<div class="kpi"${color ? ` style="border-top-color:${color}"` : ""}><div class="kpi-label">${label}</div><div class="kpi-value">${value}</div><div class="kpi-sub">${sub || "&nbsp;"}</div></div>`;
}

// ------------------------------------------------------------------------------------------ the dashboard
function dashboardModel(orders, stations, stationsInfo, now) {
  const recs = dashRecords(orders, stations);
  const createdAll = orders.map(o => Date.parse(o.created_at)).filter(Number.isFinite);
  const first = createdAll.length ? Math.min(...createdAll) : now;
  const w = DASH_WINDOWS[DASH.window] ?? Infinity;
  const x1 = Math.max(now, first + 10000), x0 = Number.isFinite(w) ? Math.max(first, x1 - w) : first;
  return { recs, x0, x1: Math.max(x1, x0 + 10000), first };
}

function dashboardKpis(recs, orders) {
  const n = recs.length, inProd = orders.length - n;
  const cnt = k => recs.filter(r => r.v[k]).length, pct = c => n ? Math.round((100 * c) / n) : 0;
  const mean = f => n ? recs.reduce((s, r) => s + f(r), 0) / n : null;
  //const tiles = [kpi("Finished", n, `${inProd} in production`)];
  const tiles = [];
  const d = mean(r => r.durMs / 1000), dl = mean(r => r.deadlineMs / 1000), e = mean(r => r.energy), b = mean(r => r.budget), q = mean(r => r.waitMs / 1000);
  tiles.push(kpi("Avg production time", d == null ? "–" : num1(d) + " s", dl == null ? "" : `avg deadline ${num1(dl)} s`));
  tiles.push(kpi("Avg energy", e == null ? "–" : num1(e), b == null ? "" : `avg budget ${num1(b)} units`));
  tiles.push(kpi("Avg time in queues", q == null ? "–" : num1(q) + " s", "per order"));
  for (const c of VIOLATION_CATS) tiles.push(kpi(c.key === "any" ? "Orders with a violation" : c.label + " violations", `${cnt(c.key)} <span class="kpi-pct">${pct(cnt(c.key))}%</span>`, `of ${n} finished`, c.color));
  return tiles.join("");
}

function dashboardCharts(m, orders, stationsInfo) {
  const { recs, x0, x1 } = m, base = { x0, x1 };
  const counts = [], pcts = [];
  for (const c of VIOLATION_CATS) {
    let k = 0;
    const cp = [], pp = [];
    recs.forEach((r, i) => { if (r.v[c.key]) k++; cp.push([r.t, k, r.id]); pp.push([r.t, (100 * k) / (i + 1), r.id]); });
    counts.push({ name: c.label, color: c.color, kind: "step", pts: cp });
    pcts.push({ name: c.label, color: c.color, kind: "line", markers: true, pts: pp });
  }
  const events = [];
  for (const o of orders) { const c = Date.parse(o.created_at); if (Number.isFinite(c)) events.push([c, +1]); if (o.finished_at) events.push([Date.parse(o.finished_at), -1]); }
  events.sort((a, b) => a[0] - b[0]);
  let wip = 0; const wipPts = events.map(([t, d]) => [t, (wip += d)]);
  const fin = recs.map((r, i) => [r.t, i + 1, r.id]);
  const ratio = f => { let a = 0, p = 0; return recs.map(r => { a += f(r); p += r.passes; return [r.t, p ? (100 * a) / p : 0, r.id]; }); };

  return [
    chartCard({ ...base, title: "Violations: number of finished orders (cumulative)", yFmt: v => String(Math.round(v)), minMax: 4, series: counts }),
    chartCard({ ...base, title: "Violations: percentage of finished orders", yFmt: v => Math.round(v) + "%", fixedMax: 100, series: pcts }),
    chartCard({ ...base, title: "Average production time (s)", yFmt: num1, series: [
      { name: "Average", color: "#eaeaea", kind: "line", pts: runningMean(recs, r => r.durMs / 1000) },
      { name: "Avg deadline", color: "#ff9d5c", kind: "line", dash: true, pts: runningMean(recs, r => r.deadlineMs / 1000) },
      { name: "Each order", color: "#4a90d9", kind: "dots", pts: dots(recs, r => r.durMs / 1000) }] }),
    chartCard({ ...base, title: "Average energy spent per order (units)", yFmt: num1, series: [
      { name: "Average", color: "#eaeaea", kind: "line", pts: runningMean(recs, r => r.energy) },
      { name: "Avg budget", color: "#e6c95c", kind: "line", dash: true, pts: runningMean(recs, r => r.budget) },
      { name: "Each order", color: "#7ee08a", kind: "dots", pts: dots(recs, r => r.energy) }] }),
    chartCard({ ...base, title: "Throughput and orders in production", yFmt: v => String(Math.round(v)), minMax: 4, series: [
      { name: "Finished (total)", color: "#7ee08a", kind: "step", pts: fin },
      { name: "In production", color: "#ff9d5c", kind: "step", pts: wipPts }] }),
    chartCard({ ...base, title: "Average time spent waiting in queues (s)", yFmt: num1, series: [
      { name: "Average", color: "#eaeaea", kind: "line", pts: runningMean(recs, r => r.waitMs / 1000) },
      { name: "Each order", color: "#c58af9", kind: "dots", pts: dots(recs, r => r.waitMs / 1000) }] }),
    chartCard({ ...base, title: "Station utilisation (share of time busy)", yFmt: v => Math.round(v) + "%", fixedMax: 100,
      note: "Rolling window; a type at 100% is the bottleneck.", series: utilisationSeries(orders, stationsInfo, x0, x1) }),
    chartCard({ ...base, title: "Faults and fast mode (share of station passes, cumulative)", yFmt: v => Math.round(v) + "%", fixedMax: 100, series: [
      { name: "Faulty passes", color: "#ff8080", kind: "line", markers: true, pts: ratio(r => r.faults) },
      { name: "Fast-mode passes", color: "#4a90d9", kind: "line", markers: true, pts: ratio(r => r.fast) }] }),
  ].join("");
}

function renderDashboard(orders, stations, stationsInfo) {
  const kpis = document.getElementById("dash-kpis"), charts = document.getElementById("dash-charts");
  if (!kpis || !charts) return;
  const now = Date.now();
  const sig = [DASH.window, orders.length, orders.filter(o => o.finished_at).length, Math.floor(now / 2000)].join("|");
  if (sig === DASH.sig) return;                                   // the x axis moves with the clock: redraw every 2 s, or when orders change
  DASH.sig = sig;
  const m = dashboardModel(orders, stations, stationsInfo, now);
  kpis.innerHTML = dashboardKpis(m.recs, orders);
  charts.innerHTML = dashboardCharts(m, orders, stationsInfo);
}

(function initDashboardControls() {
  const sel = document.getElementById("dash-window");
  if (!sel) return;
  try { const s = JSON.parse(localStorage.getItem("skt-dash") || "{}"); if (s.window in DASH_WINDOWS) DASH.window = s.window; } catch (e) { /* defaults */ }
  sel.value = DASH.window;
  sel.addEventListener("change", () => {
    DASH.window = sel.value; DASH.sig = "";
    try { localStorage.setItem("skt-dash", JSON.stringify({ window: DASH.window })); } catch (e) { /* ignore */ }
  });
})();