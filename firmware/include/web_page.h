#ifndef WEB_PAGE_H
#define WEB_PAGE_H

// ============================================================================
// Embedded HTML/CSS/JS for Radar Visualization
// Served by ESP32 Web Server at http://<ip>/
// ============================================================================

const char RADAR_HTML[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>Human Radar</title>
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body {
    background: #0a0a1a;
    color: #00ff88;
    font-family: 'Courier New', monospace;
    display: flex;
    flex-direction: column;
    align-items: center;
    min-height: 100vh;
    overflow-x: hidden;
  }
  #header {
    text-align: center;
    padding: 10px;
    width: 100%;
    background: #0d0d2b;
    border-bottom: 1px solid #1a3a1a;
  }
  #header h1 { font-size: 18px; color: #00ff88; margin-bottom: 4px; display: inline; }
  #settings-btn {
    position: absolute;
    right: 10px;
    top: 10px;
    background: #1a1a3a;
    color: #88ccff;
    border: 1px solid #2a2a4a;
    border-radius: 4px;
    padding: 5px 10px;
    font-family: 'Courier New', monospace;
    font-size: 12px;
    cursor: pointer;
    text-decoration: none;
  }
  #settings-btn:hover { background: #2a2a5a; color: #aaddff; }
  #status {
    font-size: 12px;
    padding: 2px 10px;
    border-radius: 10px;
    display: inline-block;
  }
  .connected { background: #0a3a0a; color: #00ff88; }
  .disconnected { background: #3a0a0a; color: #ff4444; }
  #radar-container {
    position: relative;
    width: 100%;
    max-width: 600px;
    margin: 10px auto;
  }
  canvas {
    width: 100%;
    height: auto;
    display: block;
    border-radius: 8px;
  }
  #info-panel {
    width: 100%;
    max-width: 600px;
    padding: 8px 12px;
    background: #0d0d2b;
    border-radius: 8px;
    margin: 5px auto;
  }
  .target-row {
    display: flex;
    justify-content: space-between;
    padding: 4px 0;
    border-bottom: 1px solid #1a1a3a;
    font-size: 13px;
  }
  .target-row:last-child { border-bottom: none; }
  .t-label { font-weight: bold; width: 60px; }
  .t-val { color: #88ccff; }
  .t-inactive { color: #333355; }
  #stats {
    font-size: 11px;
    color: #555577;
    text-align: center;
    padding: 6px;
  }
  .color-t1 { color: #ff4444; }
  .color-t2 { color: #44ff44; }
  .color-t3 { color: #4488ff; }
  #setup-banner {
    display: none;
    width: 100%;
    max-width: 600px;
    margin: 8px auto;
    padding: 12px 16px;
    background: #2a1a00;
    border: 1px solid #ff8800;
    border-radius: 8px;
    color: #ffaa33;
    font-size: 13px;
    text-align: center;
  }
  #setup-banner a {
    color: #ffdd66;
    font-weight: bold;
    text-decoration: underline;
  }
</style>
</head>
<body>

<div id="header" style="position:relative;">
  <h1>HUMAN RADAR - LD2450</h1>
  <a id="settings-btn" href="/settings">SETTINGS</a>
  <br>
  <span id="status" class="disconnected">DISCONNECTED</span>
</div>

<div id="setup-banner">
  &#9888; MQTT not configured. <a href="/settings">Open Settings</a> to connect to your MQTT broker.
</div>

<div id="radar-container">
  <canvas id="radar" width="600" height="600"></canvas>
</div>

<div id="info-panel">
  <div class="target-row">
    <span class="t-label color-t1">T1</span>
    <span id="t1-info" class="t-inactive">---</span>
  </div>
  <div class="target-row">
    <span class="t-label color-t2">T2</span>
    <span id="t2-info" class="t-inactive">---</span>
  </div>
  <div class="target-row">
    <span class="t-label color-t3">T3</span>
    <span id="t3-info" class="t-inactive">---</span>
  </div>
</div>

<div id="stats">
  Frames: <span id="frame-cnt">0</span> |
  Errors: <span id="error-cnt">0</span> |
  FPS: <span id="fps">0</span>
</div>

<script>
// ============================================================================
// Radar Visualization
// ============================================================================
const canvas = document.getElementById('radar');
const ctx = canvas.getContext('2d');
const W = canvas.width;
const H = canvas.height;
const CX = W / 2;        // Center X (sensor position)
const CY = H - 30;       // Center Y (sensor at bottom)
const MAX_RANGE = 6000;   // mm, max display range
const SCALE = (H - 60) / MAX_RANGE;

const COLORS = ['#ff4444', '#44ff44', '#4488ff'];
const statusEl = document.getElementById('status');
const tInfoEls = [
  document.getElementById('t1-info'),
  document.getElementById('t2-info'),
  document.getElementById('t3-info')
];
const frameCntEl = document.getElementById('frame-cnt');
const errorCntEl = document.getElementById('error-cnt');
const fpsEl = document.getElementById('fps');

// Trail history for each target
let trails = [[], [], []];
const TRAIL_MAX = 20;

// FPS counter
let frameTs = [];

// ============================================================================
// WebSocket Connection
// ============================================================================
let ws = null;
let reconnectTimer = null;

function connect() {
  const host = window.location.hostname;
  const port = 81;
  ws = new WebSocket('ws://' + host + ':' + port);

  ws.onopen = function() {
    statusEl.textContent = 'CONNECTED';
    statusEl.className = 'connected';
    if (reconnectTimer) { clearInterval(reconnectTimer); reconnectTimer = null; }
  };

  ws.onclose = function() {
    statusEl.textContent = 'DISCONNECTED';
    statusEl.className = 'disconnected';
    if (!reconnectTimer) { reconnectTimer = setInterval(connect, 3000); }
  };

  ws.onerror = function() { ws.close(); };

  ws.onmessage = function(evt) {
    try {
      const data = JSON.parse(evt.data);
      updateRadar(data);
    } catch(e) {}
  };
}

// ============================================================================
// Draw Radar Background
// ============================================================================
function drawBackground() {
  // Dark background
  ctx.fillStyle = '#0a0a1a';
  ctx.fillRect(0, 0, W, H);

  // Range rings (every 1000mm = 1m)
  ctx.strokeStyle = '#1a2a1a';
  ctx.lineWidth = 1;
  for (let r = 1000; r <= MAX_RANGE; r += 1000) {
    let pr = r * SCALE;
    ctx.beginPath();
    ctx.arc(CX, CY, pr, Math.PI, 2 * Math.PI);
    ctx.stroke();

    // Range label
    ctx.fillStyle = '#334433';
    ctx.font = '11px Courier New';
    ctx.textAlign = 'center';
    ctx.fillText(r / 1000 + 'm', CX + pr - 15, CY - 4);
  }

  // Angle lines (-60, -30, 0, +30, +60 degrees)
  ctx.strokeStyle = '#152015';
  let angles = [-60, -30, 0, 30, 60];
  for (let a of angles) {
    let rad = (a - 90) * Math.PI / 180;
    let ex = CX + Math.cos(rad) * MAX_RANGE * SCALE;
    let ey = CY + Math.sin(rad) * MAX_RANGE * SCALE;
    ctx.beginPath();
    ctx.moveTo(CX, CY);
    ctx.lineTo(ex, ey);
    ctx.stroke();

    // Angle label
    ctx.fillStyle = '#334433';
    ctx.font = '10px Courier New';
    let lx = CX + Math.cos(rad) * (MAX_RANGE * SCALE + 12);
    let ly = CY + Math.sin(rad) * (MAX_RANGE * SCALE + 12);
    ctx.textAlign = 'center';
    ctx.fillText(a + '\u00B0', lx, ly);
  }

  // Sensor position indicator
  ctx.fillStyle = '#00ff88';
  ctx.beginPath();
  ctx.arc(CX, CY, 5, 0, 2 * Math.PI);
  ctx.fill();
  ctx.fillStyle = '#00aa55';
  ctx.font = '10px Courier New';
  ctx.textAlign = 'center';
  ctx.fillText('SENSOR', CX, CY + 16);
}

// ============================================================================
// Draw Targets
// ============================================================================
function drawTarget(idx, x, y, speed, distance, angle, present) {
  if (!present) {
    trails[idx] = [];
    return;
  }

  // Convert mm to canvas pixels
  // X: positive = right, negative = left (sensor at bottom center)
  // Y: positive = forward (up on screen)
  let px = CX + x * SCALE;
  let py = CY - y * SCALE;

  // Clamp to canvas
  px = Math.max(10, Math.min(W - 10, px));
  py = Math.max(10, Math.min(H - 10, py));

  let color = COLORS[idx];

  // Update trail
  trails[idx].push({x: px, y: py});
  if (trails[idx].length > TRAIL_MAX) trails[idx].shift();

  // Draw trail (fading)
  for (let i = 0; i < trails[idx].length - 1; i++) {
    let alpha = (i + 1) / trails[idx].length * 0.4;
    ctx.fillStyle = color.replace(')', ',' + alpha + ')').replace('rgb', 'rgba').replace('#', '');
    // Convert hex to rgba for trail
    let r = parseInt(color.slice(1,3), 16);
    let g = parseInt(color.slice(3,5), 16);
    let b = parseInt(color.slice(5,7), 16);
    ctx.fillStyle = 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
    let sz = 3 + (i / trails[idx].length) * 4;
    ctx.beginPath();
    ctx.arc(trails[idx][i].x, trails[idx][i].y, sz, 0, 2 * Math.PI);
    ctx.fill();
  }

  // Draw main target dot
  ctx.fillStyle = color;
  ctx.shadowColor = color;
  ctx.shadowBlur = 15;
  ctx.beginPath();
  ctx.arc(px, py, 8, 0, 2 * Math.PI);
  ctx.fill();
  ctx.shadowBlur = 0;

  // Target label
  ctx.fillStyle = '#ffffff';
  ctx.font = 'bold 12px Courier New';
  ctx.textAlign = 'center';
  ctx.fillText('T' + (idx + 1), px, py - 14);

  // Speed arrow
  if (Math.abs(speed) > 1) {
    let arrowLen = Math.min(Math.abs(speed) * 2, 30);
    let dir = speed < 0 ? -1 : 1; // negative = approaching = arrow toward sensor
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(px, py);
    ctx.lineTo(px, py + dir * arrowLen);
    ctx.stroke();
    // Arrowhead
    ctx.beginPath();
    ctx.moveTo(px, py + dir * arrowLen);
    ctx.lineTo(px - 4, py + dir * (arrowLen - 6));
    ctx.lineTo(px + 4, py + dir * (arrowLen - 6));
    ctx.closePath();
    ctx.fillStyle = color;
    ctx.fill();
    ctx.lineWidth = 1;
  }
}

// ============================================================================
// Update Display
// ============================================================================
function updateRadar(data) {
  // FPS
  let now = Date.now();
  frameTs.push(now);
  while (frameTs.length > 0 && frameTs[0] < now - 1000) frameTs.shift();
  fpsEl.textContent = frameTs.length;

  // Stats
  frameCntEl.textContent = data.fc || 0;
  errorCntEl.textContent = data.ec || 0;

  // Redraw
  drawBackground();

  // Draw each target
  for (let i = 0; i < 3; i++) {
    let t = data.t[i];
    drawTarget(i, t.x, t.y, t.s, t.d, t.a, t.p);

    // Update info panel
    if (t.p) {
      let sDir = t.s < 0 ? '\u2191approaching' : '\u2193receding';
      tInfoEls[i].className = 't-val';
      tInfoEls[i].innerHTML =
        'X:<b>' + t.x + '</b>mm ' +
        'Y:<b>' + t.y + '</b>mm ' +
        'D:<b>' + t.d + '</b>mm ' +
        'A:<b>' + t.a.toFixed(1) + '</b>\u00B0 ' +
        'Spd:<b>' + Math.abs(t.s) + '</b>cm/s ' + sDir;
    } else {
      tInfoEls[i].className = 't-inactive';
      tInfoEls[i].textContent = '---';
    }
  }
}

// ============================================================================
// Setup Banner - show if MQTT not configured
// ============================================================================
fetch('/api/config').then(r=>r.json()).then(cfg=>{
  if(!cfg.me || !cfg.mh || cfg.mh.length===0){
    document.getElementById('setup-banner').style.display='block';
  }
}).catch(()=>{});

// ============================================================================
// Init
// ============================================================================
drawBackground();
connect();
</script>
</body>
</html>
)rawliteral";

#endif // WEB_PAGE_H
