const POLL_MS = 400;
const animatedOrders = new Set();
let liveMaxDurationMs = 8000;   // policy-wide fallback; an order's own deadline_ms wins
let liveEnergyBudget = 120;     // policy-wide fallback; an order's own energy_budget wins

async function fetchOrders() {
  const res = await fetch("/orders/");
  return res.json();
}

async function fetchStations() {
  const res = await fetch("/stations/");
  return res.json();
}

async function fetchPolicy() {
  const res = await fetch("/policy/");
  return res.json();
}

function lastLog(order) {
  const logs = order.production_logs || [];
  if (!logs.length) return null;
  return [...logs].sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp)).at(-1);
}

function sortedLogs(order) {
  const logs = order.production_logs || [];
  return [...logs].sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
}

function visitedPath(order) {
  const path = [];
  for (const l of sortedLogs(order)) {
    if (path.at(-1) !== l.station) path.push(l.station);
  }
  return path;
}

function completedStations(order) {
  const seen = new Set();
  const path = [];
  for (const l of sortedLogs(order)) {
    if (l.new_status === "COMPLETED" && !seen.has(l.station)) {
      seen.add(l.station);
      path.push(l.station);
    }
  }
  return path;
}

function partForStation(name) {
  const n = name.toLowerCase();

  if (n.includes("trunk")) return "trunks";
  if (n.includes("wheel")) return "wheels";
  if (n.includes("torque")) return "torque";
  if (n.includes("optional")) return "optional";

  return null;
}

const PART_LABELS = {
  trunks: "Trunks",
  wheels: "Wheels",
  torque: "Torque",
  optional: "Optional"
};

function friendlyStationName(name) {
  const part = partForStation(name);
  return part ? PART_LABELS[part] : name;
}

function abbr(station) {
  return friendlyStationName(station).slice(0, 2);
}

function checkWorkflow(order, stations) {
  const logs = sortedLogs(order);
  const visitOrder = visitedPath(order);
  const counts = {};
  for (const l of logs) counts[l.station] = (counts[l.station] || 0) + 1;
  for (const s of stations) if (!(s in counts)) return "bad";
  for (const s of stations) if (counts[s] > 2) return "bad";
  if (new Set(visitOrder).size !== visitOrder.length) return "bad";
  return "ok";
}

function appliedValues(order) {
  const logs = sortedLogs(order).filter(l => l.data_payload && Object.keys(l.data_payload).length > 0);
  if (logs.length === 0) return { front: null, rear: null, torque: null };
  const first = logs[0];
  const last = logs.at(-1);
  return {
    front: first.data_payload.color_applied ?? null,
    rear: logs.length > 1 ? (last.data_payload.color_applied ?? null) : null,
    torque: last.data_payload.torque_applied ?? null,
  };
}

function checkContent(order) {
  const applied = appliedValues(order);
  if (applied.front === null && applied.rear === null && applied.torque === null) return "unknown";
  if (applied.torque !== null && applied.torque !== order.torque_setting) return "bad";
  if (applied.front !== null && applied.front !== order.wheel_color_front) return "bad";
  if (applied.rear !== null && applied.rear !== order.wheel_color_rear) return "bad";
  return "ok";
}

// The deadline that counts is the order's own (what its customer asked for); the policy limit is only a fallback.
function checkDuration(order) {
  const start = new Date(order.created_at).getTime();
  const end = order.finished_at ? new Date(order.finished_at).getTime() : Date.now();
  const elapsed = Math.max(0, end - start);
  const own = typeof order.deadline_ms === "number";
  const max = own ? order.deadline_ms : liveMaxDurationMs;
  return { elapsed, max, own, violation: elapsed > max, remaining: max - elapsed, running: !order.finished_at };
}

// Energy used so far: the sum of what each completed pass consumed, against the order's own budget.
function checkEnergy(order) {
  let used = 0;
  const parts = [];
  for (const l of sortedLogs(order)) {
    const p = l.data_payload || {};
    if (l.new_status === "COMPLETED" && typeof p.energy_used === "number") {
      used += p.energy_used;
      parts.push({ label: `${friendlyStationName(l.station)} ${p.instance ?? l.instance ?? ""}`.trim(), energy: p.energy_used });
    }
  }
  const own = typeof order.energy_budget === "number";
  const budget = own ? order.energy_budget : liveEnergyBudget;
  return { used, budget, own, parts, violation: used > budget };
}

const fmtSec = ms => (ms / 1000).toFixed(1) + " s";
const fmtUnits = v => (Math.round(v * 10) / 10).toFixed(1);

function meter(value, max, bad) {
  const pct = Math.max(0, Math.min(100, max > 0 ? (100 * value) / max : 0));
  return `<span class="meter"><span class="meter-fill ${bad ? "bad" : "ok"}" style="width:${pct}%"></span></span>`;
}

// Why a check failed, in words, so the message can sit next to the data it is about.
function contentProblems(order) {
  const a = appliedValues(order);
  const out = [];
  if (a.torque !== null && a.torque !== order.torque_setting) out.push(`torque ${a.torque} instead of ${order.torque_setting}`);
  if (a.front !== null && a.front !== order.wheel_color_front) out.push(`front ${a.front} instead of ${order.wheel_color_front}`);
  if (a.rear !== null && a.rear !== order.wheel_color_rear) out.push(`rear ${a.rear} instead of ${order.wheel_color_rear}`);
  return out;
}

function workflowProblems(order, stations) {
  const logs = sortedLogs(order);
  const counts = {};
  for (const l of logs) counts[l.station] = (counts[l.station] || 0) + 1;
  const out = [];
  const missing = stations.filter(s => !(s in counts));
  if (missing.length) out.push(`not visited: ${missing.map(friendlyStationName).join(", ")}`);
  const many = stations.filter(s => counts[s] > 2);
  if (many.length) out.push(`too many log entries at: ${many.map(friendlyStationName).join(", ")}`);
  const visit = visitedPath(order);
  if (new Set(visit).size !== visit.length) out.push("a station was visited twice");
  return out;
}

function problemText(list) {
  return list.length ? `<span class="problem">${list.join("; ")}</span>` : "";
}

// A "step" is one visit to one station instance, e.g. { station: "Wheels", instance: "B" }.
function stepKey(s) { return `${s.station}|${s.instance ?? ""}`; }
function stepLabel(s) {
  const type = friendlyStationName(s.station);
  return s.instance ? `${type} ${s.instance}` : type;
}

function visitedSteps(order) {
  const steps = [];
  for (const l of sortedLogs(order)) {
    const step = { station: l.station, instance: (l.data_payload && l.data_payload.instance) ?? null };
    if (!steps.length || stepKey(steps.at(-1)) !== stepKey(step)) steps.push(step);
  }
  return steps;
}

// Where the order is right now according to the live station status:
// waiting in an instance's queue, or holding one of its tables.
function liveLocation(order, stationsInfo) {
  for (const info of (stationsInfo || [])) {
    const waiting = info.waiting_orders || [];
    if (waiting.includes(order.id)) {
      return { info, kind: "queued", position: waiting.indexOf(order.id) + 1 };
    }
    const table = (info.tables || []).find(t => t.order_id === order.id);
    if (table) {
      const kind = table.phase === "entrance" ? "enter" : table.phase === "exit" ? "exit" : "process";
      return { info, kind, table };
    }
  }
  return null;
}

function stepPhase(step, live) {
  if (live && live.info.station === step.station && live.info.instance === step.instance) return live.kind;
  return "exited";
}

function pathSteps(order, stationsInfo) {
  const steps = visitedSteps(order);
  const live = liveLocation(order, stationsInfo);
  if (live) {
    const ls = { station: live.info.station, instance: live.info.instance };
    if (!steps.length || stepKey(steps.at(-1)) !== stepKey(ls)) steps.push(ls);
  }
  return { steps, live };
}

function pathTrail(order, stationsInfo) {
  const { steps, live } = pathSteps(order, stationsInfo);
  return steps.map(s => {
    const phase = stepPhase(s, live);
    const chips = [];
    if (phase === "queued") chips.push({ k: "Queue", cls: "current" });
    chips.push({ k: "Enter", cls: phase === "enter" ? "current" : phase === "queued" ? "pending" : "done" });
    chips.push({ k: "Process", cls: phase === "process" ? "current" : (phase === "exit" || phase === "exited") ? "done" : "pending" });
    chips.push({ k: "Exit", cls: phase === "exit" ? "current" : phase === "exited" ? "done" : "pending" });
    const chipsHtml = chips.map(c => `<span class="phase-chip ${c.cls}">${c.k}</span>`).join("");
    return `<span class="chip-group"><span class="chip-station-label">${stepLabel(s)}</span>${chipsHtml}</span>`;
  }).join('<span class="arrow">→</span>');
}

function locationText(order, stationsInfo) {
  if (order.finished_at) return "Finished";
  const { steps, live } = pathSteps(order, stationsInfo);
  if (live) {
    const label = stepLabel({ station: live.info.station, instance: live.info.instance });
    if (live.kind === "queued") return `Queued at <b>${label}</b> (position ${live.position})`;
    if (live.kind === "enter") return `Arriving at <b>${label}</b> (${live.table.table})`;
    if (live.kind === "exit") return `Leaving <b>${label}</b> (${live.table.table})`;
    return `Processing at <b>${label}</b> (${live.table.table})`;
  }
  return steps.length ? `Between stations (last: ${stepLabel(steps.at(-1))})` : "Not started";
}

// function pathTrail(order) {
//   const path = visitedPath(order);
//   const lastStation = path.at(-1);
//   const isCurrentlyHere = order.station_status === "IN_PROGRESS";
//   return path.map((s, i) => {
//     const isCurrent = isCurrentlyHere && s === lastStation && i === path.length - 1;
//     return `<span class="chip ${isCurrent ? "current" : "done"}">${abbr(s)}</span>`;
//   }).join('<span class="arrow">→</span>');
// }

// --- Skateboard visual ---

const COLOR_MAP = { "White": "#e8e8e8", "Blue": "#4a90d9", "Green": "#5cb85c", "Orange": "#e8934a" };
function wheelColor(name) { return COLOR_MAP[name] || "#ddd"; }

function deckFillFor(speedHint) {
  if (speedHint === "fast") return "#4a2f22";
  if (speedHint === "slow") return "#22304a";
  return "#3a3a3d";
}

function gripDots(opacity) {
  let dots = "";
  for (let x = 32; x <= 88; x += 10) {
    for (let y = 22; y <= 38; y += 8) {
      dots += `<circle cx="${x}" cy="${y}" r="1" fill="#222" opacity="${opacity}"/>`;
    }
  }
  return dots;
}

const PART_DEFS = {
  trunks: {
    solid: `<rect x="22" y="14" width="5" height="32" rx="2" fill="#999"/><rect x="93" y="14" width="5" height="32" rx="2" fill="#999"/>`,
    ghost: `<rect x="22" y="14" width="5" height="32" rx="2" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="2,2" opacity="0.35"/><rect x="93" y="14" width="5" height="32" rx="2" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="2,2" opacity="0.35"/>`,
  },
  guard: {
    solid: `<line x1="35" y1="15" x2="85" y2="15" stroke="#bbb" stroke-width="3" stroke-linecap="round"/><line x1="35" y1="45" x2="85" y2="45" stroke="#bbb" stroke-width="3" stroke-linecap="round"/>`,
    ghost: `<line x1="35" y1="15" x2="85" y2="15" stroke="#555" stroke-width="2" stroke-dasharray="2,2" opacity="0.35"/><line x1="35" y1="45" x2="85" y2="45" stroke="#555" stroke-width="2" stroke-dasharray="2,2" opacity="0.35"/>`,
  },
};

function wheelsGroup(order, has) {
  if (!has) {
    return `<circle cx="24.5" cy="12" r="4" fill="none" stroke="#555" stroke-dasharray="1,2" opacity="0.35"/><circle cx="24.5" cy="48" r="4" fill="none" stroke="#555" stroke-dasharray="1,2" opacity="0.35"/><circle cx="95.5" cy="12" r="4" fill="none" stroke="#555" stroke-dasharray="1,2" opacity="0.35"/><circle cx="95.5" cy="48" r="4" fill="none" stroke="#555" stroke-dasharray="1,2" opacity="0.35"/>`;
  }
  const applied = appliedValues(order);
  const front = wheelColor(applied.front ?? order.wheel_color_front);
  const rear = wheelColor(applied.rear ?? order.wheel_color_rear);
  return `<circle cx="24.5" cy="12" r="4" fill="${front}" stroke="#222"/><circle cx="24.5" cy="48" r="4" fill="${front}" stroke="#222"/><circle cx="95.5" cy="12" r="4" fill="${rear}" stroke="#222"/><circle cx="95.5" cy="48" r="4" fill="${rear}" stroke="#222"/>`;
}

function torqueGroup(order, has) {
  if (!has) {
    return `<circle cx="35" cy="30" r="5" fill="none" stroke="#555" stroke-dasharray="1,2" opacity="0.35"/><rect x="33" y="18" width="4" height="10" rx="1" fill="none" stroke="#555" stroke-dasharray="1,2" opacity="0.35"/>`;
  }
  const applied = appliedValues(order);
  const ok = applied.torque !== null && applied.torque === order.torque_setting;
  const color = applied.torque === null ? "#999" : (ok ? "#7ee08a" : "#ff8080");
  return `<circle cx="35" cy="30" r="5" fill="none" stroke="${color}" stroke-width="2"/><rect x="33" y="16" width="4" height="12" rx="1" fill="${color}"/>`;
}

function skateboardSVG(order, speedHint) {
  //const gainedStations = visitedPath(order);
  const gainedStations = completedStations(order);
  const gainedParts = new Set(gainedStations.map(partForStation).filter(Boolean));
  const genericStations = gainedStations.filter(s => partForStation(s) === null);

  const partHtml = (part, markup) => {
    const key = `${order.id}-part-${part}`;
    const isNew = gainedParts.has(part) && !animatedOrders.has(key);
    if (isNew) animatedOrders.add(key);
    return `<g class="part ${isNew ? "part-pop" : ""}">${markup}</g>`;
  };

  const staticPartsHtml = Object.keys(PART_DEFS).map(part =>
    partHtml(part, gainedParts.has(part) ? PART_DEFS[part].solid : PART_DEFS[part].ghost)
  ).join("");

  const wheelsHtml = partHtml("wheels", wheelsGroup(order, gainedParts.has("wheels")));
  const torqueHtml = partHtml("torque", torqueGroup(order, gainedParts.has("torque")));

  const genericHtml = genericStations.map(s => {
    const key = `${order.id}-part-${s}`;
    const isNew = !animatedOrders.has(key);
    if (isNew) animatedOrders.add(key);
    return `<g class="part ${isNew ? "part-pop" : ""}">
      <circle cx="60" cy="30" r="7" fill="#333" stroke="#888" stroke-width="1"/>
      <text x="60" y="33" font-size="6" fill="#ccc" text-anchor="middle">${abbr(s)}</text>
    </g>`;
  }).join("");

  return `
    <svg viewBox="0 0 120 60" class="skate-svg">
      <path d="M8 30 Q10 18 25 16 L95 16 Q110 18 112 30 Q110 42 95 44 L25 44 Q10 42 8 30 Z" fill="${deckFillFor(speedHint)}" stroke="#666" stroke-width="1.5"/>
      ${staticPartsHtml}
      ${wheelsHtml}
      ${torqueHtml}
      ${genericHtml}
      <text x="60" y="34" font-size="18" font-weight="800" fill="#fff" text-anchor="middle" stroke="#000" stroke-width="0.8" paint-order="stroke">${order.id}</text>
    </svg>
  `;
}

function badge(status) {
  const label = { ok: "OK", bad: "violation", unknown: "n/a" }[status];
  return `<span class="badge ${status === "ok" ? "ok" : status === "bad" ? "bad" : "unknown"}">${label}</span>`;
}

// --- Factory floor layout ---
// One box per station *instance* (e.g. "Wheels A", "Wheels B"), one column per
// station type, instances stacked. Boxes are (re)built whenever the set of
// instances changes. Skate sprites are persistent DOM nodes keyed by order id
// whose left/top are updated each tick, so the CSS transition makes them slide.
// Inside a box: work row (entrance -> inside -> exit) on top, waiting queue below.
// Left to right: Empty (tall box) | one column per station type | Finished (tall box).

const TYPE_ORDER = ["Trunks", "Wheels", "Torque", "Optional"];
const BOX_W = 220, BOX_H = 190, BOX_GAP = 12, FLOOR_PAD = 10;
const EF_W = 160;        // width of the Empty / Finished boxes
let ZONES = {};          // instance label ("Wheels B") -> { type, instance, label, box }
let EF_BOX = { left: FLOOR_PAD, top: 400, width: 460, height: 130 };
let EMPTY_BOX = { left: FLOOR_PAD, top: FLOOR_PAD, width: EF_W, height: 392 };
let FINISHED_BOX = { left: 900, top: FLOOR_PAD, width: EF_W, height: 392 };

const STATION_VISUALS = {
  "Trunks":   { color: "#8a8a8f", icon: `<rect x="18" y="8" width="10" height="64" rx="3"/><rect x="72" y="8" width="10" height="64" rx="3"/>` },
  "Wheels":   { color: "#4a90d9", icon: `<circle cx="30" cy="40" r="16"/><circle cx="70" cy="40" r="16"/>` },
  "Torque":   { color: "#7ee08a", icon: `<circle cx="50" cy="32" r="14" fill="none"/><rect x="46" y="4" width="8" height="20" rx="2"/>` },
  "Optional": { color: "#bbbbbb", icon: `<path d="M20 15 Q50 2 80 15 L80 60 Q50 73 20 60 Z" fill="none"/>` },
};

function infoLabel(info) {
  return info.instance_label || `${info.station} ${info.instance ?? ""}`.trim();
}

function computeLayout(stationsInfo) {
  const byType = {};
  for (const info of stationsInfo) (byType[info.station] = byType[info.station] || []).push(info);
  const types = [...TYPE_ORDER.filter(t => byType[t]), ...Object.keys(byType).filter(t => !TYPE_ORDER.includes(t))];
  const zones = {};
  const stationsLeft = FLOOR_PAD + EF_W + BOX_GAP; // the Empty box sits to the left of the first column
  let maxRows = 1;
  types.forEach((type, col) => {
    const list = byType[type].slice().sort((a, b) => String(a.instance).localeCompare(String(b.instance)));
    maxRows = Math.max(maxRows, list.length);
    list.forEach((info, row) => {
      const label = infoLabel(info);
      zones[label] = {
        type, instance: info.instance, label,
        box: { left: stationsLeft + col * (BOX_W + BOX_GAP), top: FLOOR_PAD + row * (BOX_H + BOX_GAP), width: BOX_W, height: BOX_H },
      };
    });
  });
  const innerH = maxRows * (BOX_H + BOX_GAP) - BOX_GAP;  // as tall as the tallest station column
  const finishedLeft = stationsLeft + types.length * (BOX_W + BOX_GAP);
  return {
    zones,
    empty: { left: FLOOR_PAD, top: FLOOR_PAD, width: EF_W, height: innerH },
    finished: { left: finishedLeft, top: FLOOR_PAD, width: EF_W, height: innerH },
    width: finishedLeft + EF_W + FLOOR_PAD,
    height: innerH + 2 * FLOOR_PAD,
  };
}

function zonePoints(zone) {
  const b = zone.box;
  const y = b.top + 24;
  return {
    entrance: { left: b.left + 8, top: y },
    inside: { left: b.left + b.width / 2 - 35, top: y },
    exit: { left: b.left + b.width - 78, top: y },
  };
}

function insideSlotFor(zone, tableIndex) {
  const pts = zonePoints(zone);
  const off = (tableIndex - 1) * 8;
  return { left: pts.inside.left + off, top: pts.inside.top + off };
}

// Waiting area of an instance: 3 x 2 grid under the work row (extra skates stack with a small offset).
function queueSlot(zone, idx) {
  const b = zone.box;
  const layer = Math.floor(idx / 6), i = idx % 6;
  return { left: b.left + 3 + (i % 3) * 72 + layer * 4, top: b.top + 86 + Math.floor(i / 3) * 48 + layer * 4 };
}

// Empty / Finished boxes: skates stack top to bottom in two columns (extra skates stack with a small offset).
function columnSlot(box, idx) {
  const cols = 2, rows = Math.max(1, Math.floor((box.height - 40) / 40));
  const cap = cols * rows, layer = Math.floor(idx / cap), i = idx % cap;
  return { left: box.left + 8 + (i % cols) * 72 + layer * 5, top: box.top + 34 + Math.floor(i / cols) * 40 + layer * 5 };
}

let floorSignature = "";
const stationBoxEls = {};
const spriteEls = new Map();

function buildFloor(layout) {
  const mount = document.getElementById("stations");
  if (!mount) return;
  let floor = document.getElementById("factory-floor");
  if (!floor) {
    mount.innerHTML = `<div class="factory-floor" id="factory-floor"></div>`;
    floor = document.getElementById("factory-floor");
  }
  floor.querySelectorAll(".floor-box").forEach(el => el.remove());   // sprites are kept
  for (const k of Object.keys(stationBoxEls)) delete stationBoxEls[k];

  ZONES = layout.zones;
  EMPTY_BOX = layout.empty;
  FINISHED_BOX = layout.finished;
  floor.style.width = layout.width + "px";
  floor.style.height = layout.height + "px";

  // travel-line layer (kept between rebuilds; positions changed, so old lines are dropped)
  let layer = document.getElementById("travel-layer");
  if (!layer) {
    layer = document.createElementNS(SVG_NS, "svg");
    layer.id = "travel-layer";
    layer.setAttribute("class", "travel-layer");
    layer.innerHTML = `<g id="travel-lines"></g><g id="route-layer"></g>`;
    floor.appendChild(layer);
  }
  layer.setAttribute("width", layout.width);
  layer.setAttribute("height", layout.height);
  document.getElementById("travel-lines").innerHTML = "";
  motion.clear();
  routeSig = "";

  for (const [cls, box, text] of [["ef-empty-box", EMPTY_BOX, "Empty"], ["ef-finished-box", FINISHED_BOX, "Finished"]]) {
    const el = document.createElement("div");
    el.className = `floor-box ef-box ${cls}`;
    Object.assign(el.style, { left: box.left + "px", top: box.top + "px", width: box.width + "px", height: box.height + "px" });
    el.innerHTML = `<div class="ef-label">${text}</div>`;
    floor.appendChild(el);
  }

  for (const [label, zone] of Object.entries(ZONES)) {
    const visual = STATION_VISUALS[zone.type] || { color: "#666", icon: "" };
    const box = document.createElement("div");
    box.className = "floor-box station-box";
    box.id = "station-box-" + label.replace(/\s+/g, "-");
    Object.assign(box.style, {
      left: zone.box.left + "px", top: zone.box.top + "px",
      width: zone.box.width + "px", height: zone.box.height + "px",
      borderLeftColor: visual.color, borderLeftWidth: "4px",
    });
    box.innerHTML = `
      <div class="station-box-label"><span class="st-type">${zone.type}</span><span class="st-inst" style="background:${visual.color}">${zone.instance ?? ""}</span></div>
      <div class="station-queue-count">queue <b>0</b></div>
      <div class="station-flags"></div>
      <div class="station-effects"></div>
      <div class="station-energy"></div>
      <div class="queue-caption">waiting</div>
      <svg class="station-icon" viewBox="0 0 100 80" style="stroke:${visual.color}">${visual.icon}</svg>
    `;
    floor.appendChild(box);
    stationBoxEls[label] = box;
  }
}

// --- Travel lines and animation speed ---
// Every move leaves an arrow from the skate's current drawn position to its new one. The line is drawn while the
// skate slides (same duration, same easing), stays for a few seconds and fades. Hovering a skate shows its whole route.

const SVG_NS = "http://www.w3.org/2000/svg";
const SPRITE_W = 70, SPRITE_H = 35;
const SPEED_MS = { slow: 2000, normal: 800, fast: 400 };
const UI_PREFS = { lines: true, speed: "slow" };
const motion = new Map();      // orderId -> { left, top, zone } of the target it was last sent to
let highlightId = null;        // skate under the mouse
let routeSig = "";             // what the route layer shows now (avoids restarting its animation every poll)
let lastData = null;           // { orders, stationsInfo } of the last poll

function orderColor(id) { return `hsl(${(id * 47) % 360} 85% 62%)`; }

function applyUiPrefs() {
  document.documentElement.style.setProperty("--move-ms", SPEED_MS[UI_PREFS.speed] + "ms");
  const layer = document.getElementById("travel-layer");
  if (layer) layer.style.display = UI_PREFS.lines ? "" : "none";
}

function initControls() {
  try { Object.assign(UI_PREFS, JSON.parse(localStorage.getItem("skt-ui") || "{}")); } catch (e) { /* no storage: defaults */ }
  if (!(UI_PREFS.speed in SPEED_MS)) UI_PREFS.speed = "slow";
  const lines = document.getElementById("opt-lines"), speed = document.getElementById("opt-speed");
  if (lines) lines.checked = !!UI_PREFS.lines;
  if (speed) speed.value = UI_PREFS.speed;
  const save = () => { try { localStorage.setItem("skt-ui", JSON.stringify(UI_PREFS)); } catch (e) { /* ignore */ } applyUiPrefs(); };
  if (lines) lines.addEventListener("change", () => { UI_PREFS.lines = lines.checked; save(); });
  if (speed) speed.addEventListener("change", () => { UI_PREFS.speed = speed.value; save(); });
  applyUiPrefs();
}

function addTravelLine(orderId, x1, y1, x2, y2, betweenZones) {
  const group = document.getElementById("travel-lines");
  if (!group || !UI_PREFS.lines) return;
  const len = Math.hypot(x2 - x1, y2 - y1);
  if (len < 20) return;                                       // queue shuffles of a few pixels are not worth a line
  const dur = SPEED_MS[UI_PREFS.speed], hold = betweenZones ? 3500 : 1000, fade = 1500;
  const color = orderColor(orderId);
  const ang = Math.atan2(y2 - y1, x2 - x1), hs = betweenZones ? 9 : 6;
  const head = [[x2, y2], [x2 - hs * Math.cos(ang - 0.45), y2 - hs * Math.sin(ang - 0.45)], [x2 - hs * Math.cos(ang + 0.45), y2 - hs * Math.sin(ang + 0.45)]]
    .map(p => p.map(v => v.toFixed(1)).join(",")).join(" ");
  const g = document.createElementNS(SVG_NS, "g");
  g.setAttribute("class", "travel-seg");
  g.style.setProperty("--dur", dur + "ms");
  g.style.setProperty("--fade-delay", (dur + hold) + "ms");
  g.style.opacity = betweenZones ? "0.95" : "0.6";
  g.innerHTML = `<path class="travel-line" pathLength="1" d="M${x1.toFixed(1)} ${y1.toFixed(1)} L${x2.toFixed(1)} ${y2.toFixed(1)}" stroke="${color}" stroke-width="${betweenZones ? 3 : 1.6}"/>`
              + `<polygon class="travel-head" points="${head}" fill="${color}"/>`;
  group.appendChild(g);
  setTimeout(() => g.remove(), dur + hold + fade + 200);
  while (group.childElementCount > 80) group.firstElementChild.remove();   // never let them pile up
}

// The whole route of one order: Empty -> every station instance it used (numbered) -> Finished.
function routePoints(order, stationsInfo) {
  const centre = p => ({ x: p.left + SPRITE_W / 2, y: p.top + SPRITE_H / 2 });
  const pts = [centre(columnSlot(EMPTY_BOX, 0))];
  pathSteps(order, stationsInfo).steps.forEach((s, i) => {
    const zone = ZONES[`${s.station} ${s.instance}`];
    if (zone) pts.push({ ...centre(zonePoints(zone).inside), n: i + 1 });
  });
  if (order.finished_at) pts.push(centre(columnSlot(FINISHED_BOX, 0)));
  return pts;
}

function drawRoute() {
  const g = document.getElementById("route-layer");
  if (!g) return;
  const order = highlightId != null && lastData ? lastData.orders.find(o => o.id === highlightId) : null;
  const pts = order && UI_PREFS.lines ? routePoints(order, lastData.stationsInfo) : [];
  const sig = pts.length > 1 ? highlightId + ":" + pts.map(p => `${p.x},${p.y}`).join(" ") : "";
  if (sig === routeSig) return;
  routeSig = sig;
  if (!sig) { g.innerHTML = ""; return; }
  const color = orderColor(order.id);
  g.innerHTML = `<polyline class="route-line" points="${pts.map(p => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(" ")}" stroke="${color}" stroke-width="3" opacity="0.9"/>`
    + pts.filter(p => p.n).map(p => `<g class="route-dot"><circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="9" fill="${color}" stroke="#111" stroke-width="1"/><text x="${p.x.toFixed(1)}" y="${p.y.toFixed(1)}">${p.n}</text></g>`).join("");
}

function updateFloor(orders, stationsInfo) {
  if (!stationsInfo || !stationsInfo.length) return;
  const signature = stationsInfo.map(infoLabel).sort().join("|");
  if (signature !== floorSignature) {
    buildFloor(computeLayout(stationsInfo));
    floorSignature = signature;
  }
  const floor = document.getElementById("factory-floor");
  if (!floor) return;

  const processingByLabel = {};
  const occupiedAt = {}; // orderId -> { label, tableIndex, phase, speed, tableId }
  const queuedAt = {};   // orderId -> { label, idx }

  for (const info of stationsInfo) {
    const label = infoLabel(info);
    const box = stationBoxEls[label];
    if (box) {
      const counter = box.querySelector(".station-queue-count b");
      if (counter) counter.textContent = (info.waiting_orders || []).length;
      const flags = box.querySelector(".station-flags");
      const level = info.degradation_level || 0;
      if (flags) {
        flags.textContent = info.degraded ? `degraded (level ${level}, ${info.degraded_ops_remaining} passes left)` : "";
        flags.title = ["healthy", "mild", "moderate", "severe"][level] || "";
      }
      const eff = box.querySelector(".station-effects");
      if (eff) {
        eff.textContent = info.degraded
          ? `faults ${info.fault_chance_slow}-${info.fault_chance_fast}%  time x${info.time_factor}`
          : (info.fault_chance_slow != null ? `faults ${info.fault_chance_slow}-${info.fault_chance_fast}%` : "");
        eff.classList.toggle("degraded", !!info.degraded);
      }
      const en = box.querySelector(".station-energy");
      if (en && info.energy_slow != null) {
        en.textContent = `energy ${info.energy_slow}-${info.energy_fast}/pass`;
        en.classList.toggle("degraded", !!info.degraded);
        en.title = `nominal ${info.energy_nominal}; slow mode x0.8, fast mode x1.7${info.degraded ? "; raised by degradation" : ""}`;
      }
    }

    for (const t of (info.tables || [])) {
      if (t.order_id == null) continue;
      const m = t.table.match(/#(\d+)$/);
      const phase = t.phase || "processing";
      occupiedAt[t.order_id] = { label, tableIndex: m ? Number(m[1]) : 1, phase, speed: t.speed, tableId: t.table };
      if (phase === "processing") processingByLabel[label] = true;
    }
    (info.waiting_orders || []).forEach((oid, idx) => { queuedAt[oid] = { label, idx }; });
  }

  for (const [label, box] of Object.entries(stationBoxEls)) {
    box.classList.toggle("busy-glow", !!processingByLabel[label]);
    box.classList.toggle("processing", !!processingByLabel[label]);
  }

  const activeOrders = [...orders].sort((a, b) => b.id - a.id).slice(0, 30);
  const activeIds = new Set(activeOrders.map(o => o.id));

  for (const [id, el] of spriteEls) {
    if (!activeIds.has(id)) { el.remove(); spriteEls.delete(id); motion.delete(id); }
  }

  const slotCounters = {};
  const nextSlot = key => slotCounters[key] = (slotCounters[key] || 0) + 1;
  const emptyPos = () => columnSlot(EMPTY_BOX, nextSlot("empty") - 1);
  lastData = { orders, stationsInfo };

  // Finished box: skates are listed in the order they finished (oldest first). A new one goes to the END of the list,
  // so skates that are already there never move down; they only move up if older ones leave the 30 shown.
  const finishedRank = new Map();
  activeOrders.filter(o => o.finished_at)
    .sort((a, b) => (Date.parse(a.finished_at) - Date.parse(b.finished_at)) || (a.id - b.id))
    .forEach((o, i) => finishedRank.set(o.id, i));

  for (const order of activeOrders) {
    let el = spriteEls.get(order.id);
    if (!el) {
      el = document.createElement("div");
      el.className = "skate-sprite";
      floor.appendChild(el);
      spriteEls.set(order.id, el);
      el.addEventListener("mouseenter", () => { highlightId = order.id; drawRoute(); });
      el.addEventListener("mouseleave", () => { highlightId = null; drawRoute(); });
    }
    const occupied = occupiedAt[order.id];
    const queued = queuedAt[order.id];

    // Tag under the skate: which instance it is using (solid) or waiting for (dashed).
    el.innerHTML = skateboardSVG(order, occupied?.speed || null);

    let pos, zoneKey;     // zoneKey: which box the skate is in (station instance label, "empty" or "finished")
    if (order.finished_at) {
      pos = columnSlot(FINISHED_BOX, finishedRank.get(order.id)); zoneKey = "finished";
    } else if (occupied) {
      const zone = ZONES[occupied.label];
      zoneKey = zone ? occupied.label : "empty";
      if (!zone) pos = emptyPos();
      else if (occupied.phase === "entrance") pos = zonePoints(zone).entrance;
      else if (occupied.phase === "exit") pos = zonePoints(zone).exit;
      else pos = insideSlotFor(zone, occupied.tableIndex);
    } else if (queued) {
      const zone = ZONES[queued.label];
      zoneKey = zone ? queued.label : "empty";
      pos = zone ? queueSlot(zone, queued.idx) : emptyPos();
    } else {
      const steps = visitedSteps(order);
      const label = steps.length ? `${steps.at(-1).station} ${steps.at(-1).instance}` : null;
      const zone = label ? ZONES[label] : null;
      zoneKey = zone ? label : "empty";
      pos = zone ? zonePoints(zone).exit : emptyPos();
    }

    // A new target: draw the line from where the skate is drawn right now (it may still be sliding) to where it is going.
    const prev = motion.get(order.id);
    if (prev && (Math.abs(prev.left - pos.left) > 1 || Math.abs(prev.top - pos.top) > 1)) {
      addTravelLine(order.id, el.offsetLeft + SPRITE_W / 2, el.offsetTop + SPRITE_H / 2,
                    pos.left + SPRITE_W / 2, pos.top + SPRITE_H / 2, prev.zone !== zoneKey);
    }
    motion.set(order.id, { left: pos.left, top: pos.top, zone: zoneKey });

    el.style.left = pos.left + "px";
    el.style.top = pos.top + "px";
  }
  drawRoute();
}

function renderTable(orders, stations, stationsInfo) {
  const body = document.getElementById("orders-body");
  if (!body) return;

  const rows = [...orders].sort((a, b) => b.id - a.id).slice(0, 30);
  body.innerHTML = rows.map(o => {
    const applied = appliedValues(o);
    const duration = checkDuration(o);
    const energy = checkEnergy(o);
    const workflow = checkWorkflow(o, stations);
    const content = checkContent(o);
    const late = duration.remaining < 0;
    const deadlineNote = duration.running
      ? (late ? `<span class="problem">overdue by ${fmtSec(-duration.remaining)}</span>` : `${fmtSec(duration.remaining)} left`)
      : (late ? `<span class="problem">finished ${fmtSec(-duration.remaining)} late</span>` : `finished ${fmtSec(duration.remaining)} early`);
    const terms = [
      o.price != null ? `Price: ${o.price}` : null,
      o.source ? `Source: ${o.source}` : null,
      o.focus && (o.focus.time || o.focus.energy || o.focus.quality)
        ? `Focus: time ${Math.round(100 * o.focus.time / (o.focus.time + o.focus.energy + o.focus.quality))}% / energy ${Math.round(100 * o.focus.energy / (o.focus.time + o.focus.energy + o.focus.quality))}% / quality ${Math.round(100 * o.focus.quality / (o.focus.time + o.focus.energy + o.focus.quality))}%`
        : null,
    ].filter(Boolean).join(" &nbsp;&nbsp; ");
    return `
    <tr>
      <td>${o.id}</td>
      <td>${o.client_name}</td>
      <td class="path-cell">${pathTrail(o, stationsInfo)}<div class="mini-skate">${skateboardSVG(o)}</div></td>
      <td>${o.station_status}</td>
    </tr>
    <tr class="detail-row">
      <td class="detail-label">Station</td>
      <td colspan="3">${locationText(o, stationsInfo)} &nbsp; ${workflow === "ok" ? "" : badge(workflow) + " " + problemText(workflowProblems(o, stations))}</td>
    </tr>
    <tr class="detail-row">
      <td class="detail-label">Wanted</td>
      <td colspan="3">Front: ${o.wheel_color_front} &nbsp;&nbsp; Rear: ${o.wheel_color_rear} &nbsp;&nbsp; Torque: ${o.torque_setting}${terms ? "<br><span class=\"muted\">" + terms + "</span>" : ""}</td>
    </tr>
    <tr class="detail-row">
      <td class="detail-label">Applied</td>
      <td colspan="3">Front: ${applied.front ?? "--"} &nbsp;&nbsp; Rear: ${applied.rear ?? "--"} &nbsp;&nbsp; Torque: ${applied.torque ?? "--"} &nbsp; ${badge(content)} ${problemText(contentProblems(o))}</td>
    </tr>
    <tr class="detail-row">
      <td class="detail-label">Deadline</td>
      <td colspan="3">${meter(duration.elapsed, duration.max, duration.violation)} ${fmtSec(duration.elapsed)} of ${fmtSec(duration.max)}${duration.own ? "" : " (policy limit)"} &nbsp; ${badge(duration.violation ? "bad" : "ok")} <span class="muted">${deadlineNote}</span></td>
    </tr>
    <tr class="detail-row">
      <td class="detail-label">Energy</td>
      <td colspan="3">${meter(energy.used, energy.budget, energy.violation)} ${fmtUnits(energy.used)} of ${fmtUnits(energy.budget)} units${energy.own ? "" : " (policy limit)"} &nbsp; ${badge(energy.parts.length ? (energy.violation ? "bad" : "ok") : "unknown")}${energy.violation ? ` <span class="problem">${fmtUnits(energy.used - energy.budget)} units over budget</span>` : ""}${energy.parts.length ? `<br><span class="muted">${energy.parts.map(p => `${p.label} ${fmtUnits(p.energy)}`).join(" &nbsp;+&nbsp; ")}</span>` : ""}</td>
    </tr>
  `;
  }).join("");
}

async function tick() {
  try {
    const [orders, stationsInfo] = await Promise.all([fetchOrders(), fetchStations()]);
    try {
      const policy = await fetchPolicy();
      if (policy && typeof policy.max_duration_ms === "number") liveMaxDurationMs = policy.max_duration_ms;
      if (policy && typeof policy.energy_budget === "number") liveEnergyBudget = policy.energy_budget;
    } catch (e) {
      // /policy/ not available -- keep the fallback default.
    }
    const stations = [...new Set((stationsInfo || []).map(s => s.station))];
    updateFloor(orders || [], stationsInfo || []);
    renderTable(orders || [], stations, stationsInfo || []);
    try { 
      renderDashboard(orders || [], stations, stationsInfo || []); 
    } catch (e) { 
      console.error("dashboard failed", e); 
    }
  } catch (e) {
    console.error("poll failed", e);
  }
}

initControls();
tick();
setInterval(tick, POLL_MS);