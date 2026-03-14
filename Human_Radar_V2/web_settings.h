#ifndef WEB_SETTINGS_H
#define WEB_SETTINGS_H

// ============================================================================
// Settings Page HTML - accessible at http://<ip>/settings
// ============================================================================

const char SETTINGS_HTML[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>Radar Settings</title>
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body {
    background: #0a0a1a;
    color: #cccccc;
    font-family: 'Courier New', monospace;
    padding: 20px;
    max-width: 500px;
    margin: 0 auto;
  }
  h1 { color: #00ff88; font-size: 20px; text-align: center; margin-bottom: 20px; }
  h2 { color: #88ccff; font-size: 15px; margin: 20px 0 10px; padding-bottom: 5px; border-bottom: 1px solid #1a2a3a; }
  .card {
    background: #0d0d2b;
    border: 1px solid #1a2a3a;
    border-radius: 8px;
    padding: 15px;
    margin-bottom: 15px;
  }
  label {
    display: block;
    font-size: 12px;
    color: #888899;
    margin-bottom: 4px;
    margin-top: 10px;
  }
  label:first-child { margin-top: 0; }
  input, select {
    width: 100%;
    padding: 8px 10px;
    background: #1a1a3a;
    border: 1px solid #2a2a4a;
    border-radius: 4px;
    color: #ffffff;
    font-family: 'Courier New', monospace;
    font-size: 14px;
  }
  input:focus, select:focus {
    outline: none;
    border-color: #00ff88;
  }
  select { cursor: pointer; }
  option { background: #1a1a3a; }
  .btn {
    display: inline-block;
    padding: 10px 20px;
    border: none;
    border-radius: 4px;
    font-family: 'Courier New', monospace;
    font-size: 14px;
    cursor: pointer;
    margin-top: 15px;
    width: 100%;
  }
  .btn-save { background: #00aa55; color: #fff; }
  .btn-save:hover { background: #00cc66; }
  .btn-reset { background: #aa3333; color: #fff; margin-top: 8px; }
  .btn-reset:hover { background: #cc4444; }
  .btn-back { background: #333355; color: #ccc; margin-top: 8px; }
  .btn-back:hover { background: #444466; }
  .msg {
    padding: 10px;
    border-radius: 4px;
    margin-bottom: 15px;
    text-align: center;
    display: none;
    font-size: 13px;
  }
  .msg-ok { background: #0a3a0a; color: #00ff88; border: 1px solid #00aa55; }
  .msg-err { background: #3a0a0a; color: #ff4444; border: 1px solid #aa3333; }
  .info {
    font-size: 11px;
    color: #555577;
    margin-top: 6px;
    line-height: 1.4;
  }
  .status-row {
    display: flex;
    justify-content: space-between;
    padding: 4px 0;
    font-size: 12px;
  }
  .status-label { color: #888899; }
  .status-val { color: #00ff88; }
  .toggle-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin: 8px 0;
  }
  .toggle-label {
    font-size: 13px;
    color: #cccccc;
  }
  .switch {
    position: relative;
    width: 48px;
    height: 26px;
    flex-shrink: 0;
  }
  .switch input { opacity: 0; width: 0; height: 0; }
  .slider {
    position: absolute;
    cursor: pointer;
    top: 0; left: 0; right: 0; bottom: 0;
    background: #2a2a4a;
    border-radius: 26px;
    transition: 0.3s;
  }
  .slider:before {
    content: "";
    position: absolute;
    height: 20px;
    width: 20px;
    left: 3px;
    bottom: 3px;
    background: #555577;
    border-radius: 50%;
    transition: 0.3s;
  }
  .switch input:checked + .slider {
    background: #00aa55;
  }
  .switch input:checked + .slider:before {
    transform: translateX(22px);
    background: #ffffff;
  }
</style>
</head>
<body>

<h1>RADAR SETTINGS</h1>

<div id="msg" class="msg"></div>

<!-- Current Status -->
<div class="card">
  <h2>Current Status</h2>
  <div class="status-row">
    <span class="status-label">WiFi Mode:</span>
    <span class="status-val" id="cur-mode">---</span>
  </div>
  <div class="status-row">
    <span class="status-label">SSID:</span>
    <span class="status-val" id="cur-ssid">---</span>
  </div>
  <div class="status-row">
    <span class="status-label">IP Address:</span>
    <span class="status-val" id="cur-ip">---</span>
  </div>
  <div class="status-row">
    <span class="status-label">Device Name:</span>
    <span class="status-val" id="cur-name">---</span>
  </div>
  <div class="status-row">
    <span class="status-label">MQTT:</span>
    <span class="status-val" id="cur-mqtt">---</span>
  </div>
</div>

<!-- WiFi Settings -->
<div class="card">
  <h2>WiFi Connection</h2>
  <label>Mode</label>
  <select id="wifi_mode">
    <option value="0">Access Point (ESP32 creates WiFi)</option>
    <option value="1">Station (Connect to existing WiFi)</option>
  </select>

  <div id="sta-fields">
    <label>WiFi SSID</label>
    <input type="text" id="wifi_ssid" maxlength="32" placeholder="Your WiFi name">

    <label>WiFi Password</label>
    <input type="password" id="wifi_pass" maxlength="64" placeholder="Your WiFi password">
  </div>

  <p class="info">
    AP Mode: Connect to "HumanRadar" WiFi, open http://192.168.4.1<br>
    STA Mode: ESP32 connects to your WiFi. Check Serial for IP.
  </p>
</div>

<!-- MQTT Settings -->
<div class="card">
  <h2>MQTT</h2>
  <div class="status-row" style="margin-bottom:8px;">
    <span class="status-label">Status:</span>
    <span class="status-val" id="mqtt-status">---</span>
  </div>

  <div class="toggle-row">
    <span class="toggle-label">Enable MQTT</span>
    <label class="switch">
      <input type="checkbox" id="mqtt_en" onchange="toggleMqttFields()">
      <span class="slider"></span>
    </label>
  </div>

  <div id="mqtt-fields">
    <label>Protocol</label>
    <select id="mqtt_proto" onchange="onProtoChange()">
      <option value="0">ws</option>
      <option value="1">wss</option>
      <option value="2" selected>mqtt / tcp</option>
      <option value="3">mqtts / tls</option>
    </select>

    <label>Broker Host / IP</label>
    <input type="text" id="mqtt_host" maxlength="64" placeholder="e.g. 192.168.1.100 or broker.hivemq.com">

    <label>Port</label>
    <input type="number" id="mqtt_port" value="1883" min="1" max="65535">

    <label>Username (optional)</label>
    <input type="text" id="mqtt_user" maxlength="32" placeholder="Leave empty if not required">

    <label>Password (optional)</label>
    <input type="password" id="mqtt_pass" maxlength="64" placeholder="Leave empty if not required">
  </div>

  <p class="info">
    Topic: humanradar/{device_name}/targets (JSON, 10 Hz)<br>
    LWT: humanradar/{device_name}/status (online/offline)
  </p>
</div>

<!-- Device Settings -->
<div class="card">
  <h2>Device</h2>
  <label>Device Name</label>
  <input type="text" id="dev_name" maxlength="32" placeholder="HumanRadar">
</div>

<!-- Sensor Settings -->
<div class="card">
  <h2>Sensor / Detection</h2>

  <label>Publish Interval (ms) <span class="info" style="display:inline">[50-2000]</span></label>
  <input type="number" id="pub_int" value="100" min="50" max="2000" step="10">

  <label>Unmanned Delay (ms) <span class="info" style="display:inline">[1000-60000]</span></label>
  <input type="number" id="unm_dly" value="5000" min="1000" max="60000" step="500">

  <label>Target Timeout (ms) <span class="info" style="display:inline">[100-10000]</span></label>
  <input type="number" id="tgt_tout" value="1000" min="100" max="10000" step="100">

  <div class="toggle-row">
    <span class="toggle-label">Multi-Target Mode (up to 3)</span>
    <label class="switch">
      <input type="checkbox" id="multi_tgt" checked>
      <span class="slider"></span>
    </label>
  </div>

  <label>Sensitivity: <span id="sn_val">5</span> <span class="info" style="display:inline">[0-9]</span></label>
  <input type="range" id="sensitivity" min="0" max="9" value="5"
    style="background:transparent;border:none;padding:0;"
    oninput="document.getElementById('sn_val').textContent=this.value">

  <p class="info">
    Publish Interval: MQTT data rate. Lower = faster updates, more bandwidth.<br>
    Unmanned Delay: Time with no targets before "no presence" status.<br>
    Target Timeout: How long before a lost target is removed.<br>
    Sensitivity: Software filter (0=most strict, 9=least strict).
  </p>
</div>

<!-- Firmware Info -->
<div class="card">
  <h2>Firmware</h2>
  <div class="status-row">
    <span class="status-label">Version:</span>
    <span class="status-val" id="fw-ver">---</span>
  </div>
</div>

<!-- Buttons -->
<button class="btn btn-save" onclick="saveSettings()">SAVE & RESTART</button>
<button class="btn btn-reset" onclick="resetDefaults()">RESET TO DEFAULTS</button>
<a href="/"><button class="btn btn-back" type="button">BACK TO RADAR</button></a>

<script>
// Load current config on page load
fetch('/api/config')
  .then(r => r.json())
  .then(cfg => {
    document.getElementById('wifi_mode').value = cfg.wm;
    document.getElementById('wifi_ssid').value = cfg.ws || '';
    document.getElementById('wifi_pass').value = cfg.wp || '';
    document.getElementById('mqtt_en').checked = cfg.me == 1;
    document.getElementById('mqtt_proto').value = cfg.mr != null ? cfg.mr : 2;
    document.getElementById('mqtt_host').value = cfg.mh || '';
    document.getElementById('mqtt_port').value = cfg.mp || 1883;
    document.getElementById('mqtt_user').value = cfg.mu || '';
    document.getElementById('mqtt_pass').value = cfg.mpp || '';
    document.getElementById('dev_name').value  = cfg.dn || 'HumanRadar';

    // Sensor config
    document.getElementById('pub_int').value = cfg.pi || 100;
    document.getElementById('unm_dly').value = cfg.ud || 5000;
    document.getElementById('tgt_tout').value = cfg.tt || 1000;
    document.getElementById('multi_tgt').checked = cfg.mt == 1;
    document.getElementById('sensitivity').value = cfg.sn != null ? cfg.sn : 5;
    document.getElementById('sn_val').textContent = cfg.sn != null ? cfg.sn : 5;
    document.getElementById('fw-ver').textContent = cfg.fw || '---';

    // Status display
    document.getElementById('cur-mode').textContent = cfg.wm == 1 ? 'Station' : 'Access Point';
    document.getElementById('cur-ssid').textContent = cfg.wm == 1 ? (cfg.ws || '---') : 'HumanRadar';
    document.getElementById('cur-ip').textContent = cfg.ip || '---';
    document.getElementById('cur-name').textContent = cfg.dn || 'HumanRadar';
    var mqttSt = cfg.ms || 'disabled';
    var protoNames = ['ws','wss','mqtt/tcp','mqtts/tls'];
    var protoLabel = protoNames[cfg.mr != null ? cfg.mr : 2] || 'mqtt/tcp';
    var mqttDisplay = mqttSt === 'disabled' ? mqttSt : mqttSt + ' (' + protoLabel + ')';
    document.getElementById('mqtt-status').textContent = mqttDisplay;
    document.getElementById('mqtt-status').style.color = mqttSt === 'connected' ? '#00ff88' : (mqttSt === 'disconnected' ? '#ff4444' : '#555577');
    document.getElementById('cur-mqtt').textContent = mqttDisplay;
    document.getElementById('cur-mqtt').style.color = mqttSt === 'connected' ? '#00ff88' : (mqttSt === 'disconnected' ? '#ff4444' : '#555577');

    toggleStaFields();
    toggleMqttFields();
  })
  .catch(() => showMsg('Failed to load config', true));

document.getElementById('wifi_mode').addEventListener('change', toggleStaFields);
function toggleStaFields() {
  const mode = document.getElementById('wifi_mode').value;
  document.getElementById('sta-fields').style.display = mode === '1' ? 'block' : 'none';
}

function toggleMqttFields() {
  const en = document.getElementById('mqtt_en').checked;
  document.getElementById('mqtt-fields').style.display = en ? 'block' : 'none';
}

var defaultPorts = {0: 8083, 1: 8084, 2: 1883, 3: 8883};
var lastAutoPort = true;
function onProtoChange() {
  var proto = document.getElementById('mqtt_proto').value;
  var portEl = document.getElementById('mqtt_port');
  var curPort = parseInt(portEl.value);
  // Auto-set port if it was a default port or empty
  var isDefault = Object.values(defaultPorts).indexOf(curPort) >= 0 || !curPort;
  if (isDefault) {
    portEl.value = defaultPorts[proto] || 1883;
  }
}

function showMsg(text, isError) {
  const el = document.getElementById('msg');
  el.textContent = text;
  el.className = 'msg ' + (isError ? 'msg-err' : 'msg-ok');
  el.style.display = 'block';
  setTimeout(() => { el.style.display = 'none'; }, 5000);
}

function saveSettings() {
  const data = {
    wm: parseInt(document.getElementById('wifi_mode').value),
    ws: document.getElementById('wifi_ssid').value,
    wp: document.getElementById('wifi_pass').value,
    me: document.getElementById('mqtt_en').checked ? 1 : 0,
    mr: parseInt(document.getElementById('mqtt_proto').value),
    mh: document.getElementById('mqtt_host').value,
    mp: parseInt(document.getElementById('mqtt_port').value) || 1883,
    mu: document.getElementById('mqtt_user').value,
    mpp: document.getElementById('mqtt_pass').value,
    dn: document.getElementById('dev_name').value || 'HumanRadar',
    pi: parseInt(document.getElementById('pub_int').value) || 100,
    ud: parseInt(document.getElementById('unm_dly').value) || 5000,
    tt: parseInt(document.getElementById('tgt_tout').value) || 1000,
    mt: document.getElementById('multi_tgt').checked ? 1 : 0,
    sn: parseInt(document.getElementById('sensitivity').value) || 5
  };

  fetch('/api/config', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(data)
  })
  .then(r => r.json())
  .then(res => {
    if (res.ok) {
      showMsg('Settings saved! Restarting in 3 seconds...', false);
      setTimeout(() => { location.reload(); }, 5000);
    } else {
      showMsg('Failed to save: ' + (res.error || 'Unknown error'), true);
    }
  })
  .catch(() => showMsg('Connection lost. ESP32 is restarting...', false));
}

function resetDefaults() {
  if (!confirm('Reset ALL settings to factory defaults?')) return;
  fetch('/api/reset', { method: 'POST' })
    .then(() => {
      showMsg('Reset done! Restarting...', false);
      setTimeout(() => { location.reload(); }, 5000);
    })
    .catch(() => showMsg('Connection lost. ESP32 is restarting...', false));
}
</script>
</body>
</html>
)rawliteral";

#endif // WEB_SETTINGS_H
