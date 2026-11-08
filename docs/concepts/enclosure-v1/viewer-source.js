import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { CSS2DRenderer, CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';

// Units in the concept scene are millimetres. GLB export scales to metres.
const stage = document.getElementById('stage');
const status = document.getElementById('render-status');
const downloadStatus = document.getElementById('download-status');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const notes = {
  front: ['01 / ENCLOSURE', 'ผิวหน้าเรียบ ให้เรดาร์มองผ่าน', 'แผ่นพลาสติกด้านบนปิดเสาอากาศ ไม่ใช้ตะแกรงโลหะหรือสติกเกอร์ฟอยล์ในแนวตรวจจับ ความหนาและระยะจริงต้องทดลอง RF ก่อนผลิต'],
  radar: ['02 / SENSING', 'เสาอากาศอยู่ด้านบนและหันออกหน้า', 'ใช้กรอบขนาด HLK-LD2450 44 × 15 mm วางแนวนอน เผื่อระยะจากเสาอากาศถึงด้านในฝาหน้าประมาณ 12.4 mm ตามแนวทางผู้ผลิต ตำแหน่งและการยึดต้องทดสอบกับโมดูลจริง'],
  esp32: ['03 / COMPUTE', 'บอร์ดอยู่กลางกล่อง เข้าถึงสายได้', 'ขนาดบอร์ดสมมติ 28 × 54 mm แยกออกจากแนวหน้าเรดาร์ และยกบริเวณเสาอากาศ WiFi เหนือแบตในร่างนี้ จุดยึด พื้นที่เสาอากาศ และปลั๊กยังต้องตรวจตามรุ่นจริง'],
  battery: ['04 / ENERGY', 'แบตแบนอยู่ด้านหลังและด้านล่าง', 'แพ็ก LiPo 1S สมมติ 35 × 55 × 6 mm มีพื้นที่รองรับและระยะเผื่อ ไม่ใช้ฝาหรือสกรูกดอัดเซลล์ ขนาดและวิธียึดจริงจะปรับหลังวัดแบตของคุณ'],
  power: ['05 / RESERVED VOLUME', 'เผื่อชุดจ่ายไฟเดิมและช่องเสียบ', 'แผ่นสีเขียวล่างเป็นตัวแทนพื้นที่ 46 × 22 mm สำหรับจัดวางชุดชาร์จ/แปลงไฟเดิม ไม่ใช่การออกแบบวงจรใหม่ รูพอร์ตเป็นรูปทรงสมมติ ต้องย้ายให้ตรงโมดูลจริง'],
  rear: ['06 / SERVICE', 'เปิดฝาหลังเพื่อประกอบและซ่อม', 'ฝาหลังแยกจากตัวเรือน ใช้ตำแหน่งสกรู 4 จุดแบบสมมติ มีพื้นที่รองแบตและจัดสาย ก่อนผลิตต้องกำหนดเสาเกลียว ความเผื่อ และทางเดินสายให้ตรงชิ้นส่วนจริง']
};
let selectPart3D = () => {};
// Textual explanations remain usable even when WebGL is unavailable.
for (const button of document.querySelectorAll('[data-part]')) button.addEventListener('click', () => {
  for (const item of document.querySelectorAll('[data-part]')) {
    const active = item === button;
    item.classList.toggle('active', active); item.setAttribute('aria-pressed', String(active));
  }
  const note = notes[button.dataset.part];
  document.getElementById('part-type').textContent = note[0];
  document.getElementById('part-title').textContent = note[1];
  document.getElementById('part-description').textContent = note[2];
  selectPart3D(button.dataset.part);
});

function roundedPath(w, h, r, cx = 0, cy = 0, PathType = THREE.Shape) {
  const p = new PathType(), x = cx - w / 2, y = cy - h / 2;
  p.moveTo(x + r, y);
  p.lineTo(x + w - r, y); p.quadraticCurveTo(x + w, y, x + w, y + r);
  p.lineTo(x + w, y + h - r); p.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  p.lineTo(x + r, y + h); p.quadraticCurveTo(x, y + h, x, y + h - r);
  p.lineTo(x, y + r); p.quadraticCurveTo(x, y, x + r, y);
  return p;
}

function start() {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.8));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.35;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.domElement.setAttribute('aria-hidden', 'true');
  stage.prepend(renderer.domElement);
  const labels = new CSS2DRenderer();
  labels.domElement.className = 'labels';
  labels.domElement.setAttribute('aria-hidden', 'true');
  stage.append(labels.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(34, 1, 0.1, 2000);
  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.1;
  controls.minDistance = 150;
  controls.maxDistance = 650;
  controls.maxPolarAngle = Math.PI * 0.86;
  controls.autoRotateSpeed = 0.65;
  controls.target.set(0, 0, 0);
  scene.add(new THREE.HemisphereLight(0xf8faf0, 0x727d67, 2.3));
  const key = new THREE.DirectionalLight(0xffffff, 3.2);
  key.position.set(110, 210, 170);
  key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048);
  key.shadow.camera.left = -180; key.shadow.camera.right = 180;
  key.shadow.camera.top = 180; key.shadow.camera.bottom = -180;
  key.shadow.camera.near = 10; key.shadow.camera.far = 600;
  key.shadow.normalBias = 0.7;
  key.shadow.bias = -0.0003;
  key.shadow.radius = 5;
  scene.add(key);
  const fill = new THREE.DirectionalLight(0xe8f1dc, 1.4);
  fill.position.set(-140, 60, -100); scene.add(fill);
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(2000, 2000),
    new THREE.MeshStandardMaterial({ color: 0xe7e9e0, roughness: 1 }));
  floor.rotation.x = -Math.PI / 2; floor.position.y = -57.2;
  floor.receiveShadow = true; scene.add(floor);
  const grid = new THREE.GridHelper(240, 24, 0xc3cbb9, 0xd3d9c9);
  grid.position.y = -57.08;
  grid.material.transparent = true; grid.material.opacity = 0.55; scene.add(grid);

  const assembly = new THREE.Group(); assembly.name = 'Human_Radar_Concept_01';
  assembly.userData = { conceptOnly: true, envelopeMm: [78, 112, 46], batteryAssumedMm: [35, 55, 6], dimensionsToVerify: true };
  scene.add(assembly);
  const parts = {};
  for (const id of ['shell', 'rear', 'battery', 'esp32', 'power', 'radar', 'front']) {
    const group = new THREE.Group(); group.name = id;
    assembly.add(group); parts[id] = group;
  }
  const standard = (color, roughness = 0.6, metalness = 0) =>
    new THREE.MeshStandardMaterial({ color, roughness, metalness });
  const charcoal = standard(0x28312e, 0.67);
  const frontMaterial = standard(0x323c36, 0.7);
  const polymer = standard(0xe5e1d2, 0.68);
  const boardMaterial = standard(0x215749, 0.55);
  const blackBoard = standard(0x192b28, 0.62);
  const gold = standard(0xc8a45a, 0.37, 0.65);
  const metal = standard(0xb7bec0, 0.38, 0.75);
  const dark = standard(0x111a18, 0.6);
  const amber = standard(0xd89e36, 0.4, 0.15);
  const foil = standard(0xcbd0c6, 0.44, 0.6);
  function mesh(group, geometry, material, name, x = 0, y = 0, z = 0) {
    const object = new THREE.Mesh(geometry, material);
    object.name = name; object.position.set(x, y, z);
    object.castShadow = true; object.receiveShadow = true;
    object.userData.baseOpacity = material.opacity;
    object.userData.baseTransparent = material.transparent;
    group.add(object); return object;
  }
  function box(group, w, h, d, mat, name, x, y, z) {
    return mesh(group, new THREE.BoxGeometry(w, h, d), mat, name, x, y, z);
  }
  function plate(group, w, h, radius, depth, mat, name, x, y, z, holes = []) {
    const shape = roundedPath(w, h, radius);
    for (const hole of holes) shape.holes.push(roundedPath(...hole, THREE.Path));
    const geometry = new THREE.ExtrudeGeometry(shape, {
      depth, bevelEnabled: false, curveSegments: 12, steps: 1
    });
    return mesh(group, geometry, mat, name, x, y, z - depth / 2);
  }
  function cylinder(group, radius, depth, mat, name, x, y, z) {
    const object = mesh(group, new THREE.CylinderGeometry(radius, radius, depth, 24), mat, name, x, y, z);
    object.rotation.x = Math.PI / 2; return object;
  }

  // Open centre frame; rear and front are separate parts, not a solid block.
  plate(parts.shell, 78, 112, 10, 42.8, charcoal, 'Hollow_polymer_body_2mm', 0, 0, 0.4,
    [[74, 108, 8, 0, 0]]);
  plate(parts.rear, 78, 112, 10, 2, charcoal, 'Removable_rear_cover', 0, 0, -22);
  for (const x of [-31, 31]) for (const y of [-47, 47]) {
    cylinder(parts.rear, 2.6, 1.0, dark, 'Rear_fastener_placeholder', x, y, -23.15);
    box(parts.rear, 2.8, 0.45, 0.2, metal, 'Fastener_slot', x, y, -23.72);
    cylinder(parts.shell, 3.4, 7, charcoal, 'Moulded_fastener_boss', x, y, -17.5);
    cylinder(parts.shell, 1.35, 7.05, gold, 'Thread_insert_placeholder', x, y, -17.5);
  }
  // Window replaces the front material here rather than adding another layer.
  plate(parts.front, 78, 112, 10, 1.2, frontMaterial, 'Front_cover', 0, 0, 22.4,
    [[58.4, 28.4, 5, 0, 38]]);
  plate(parts.front, 58, 28, 5, 1.2, polymer, 'Nonmetal_RF_window_1_2mm', 0, 38, 22.4);
  const ledMaterial = new THREE.MeshStandardMaterial({
    color: 0xc6fc78, emissive: 0x8dd332, emissiveIntensity: 0.8, roughness: 0.3
  });
  cylinder(parts.front, 1.45, 1.1, ledMaterial, 'Status_LED_concept', 0, 14, 23.2);
  function textPlate(text, width, height, x, y, z) {
    const canvas = document.createElement('canvas');
    canvas.width = 1024; canvas.height = 128;
    const context = canvas.getContext('2d');
    context.fillStyle = '#a3afa1'; context.font = '500 48px sans-serif';
    context.textAlign = 'center'; context.textBaseline = 'middle';
    context.fillText(text, 512, 64);
    const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
    const material = new THREE.MeshBasicMaterial({ map: texture, transparent: true, depthWrite: false });
    const label = mesh(parts.front, new THREE.PlaneGeometry(width, height), material, 'Nonmetal_name_mark', x, y, z);
    label.castShadow = false;
  }
  textPlate('HUMAN RADAR V2', 43, 5.4, 0, -40, 23.02);
  box(parts.shell, 0.5, 6.0, 12.0, metal, 'Charge_port_rim_placeholder', 39.12, -38, 0);
  box(parts.shell, 0.6, 4.1, 9.8, dark, 'Charge_port_opening_placeholder', 39.45, -38, 0);
  box(parts.shell, 0.7, 0.8, 6.5, metal, 'Charge_port_tongue_placeholder', 39.78, -38, 0);
  for (let i = 0; i < 4; i++) {
    box(parts.shell, 0.4, 1.0, 7.5, dark, 'Side_sound_slot_placeholder', -39.15, -29 + i * 3.1, 0);
  }

  // Antenna face = z 9.4; RF-window inside = z 21.8 => nominal 12.4 mm.
  plate(parts.radar, 44, 15, 1.5, 1.6, boardMaterial, 'LD2450_board_44x15', 0, 38, 8.6);
  for (const x of [-13.5, 0, 13.5]) {
    box(parts.radar, 8.8, 8.3, 0.12, gold, 'Planar_radar_antenna_illustration', x, 38, 9.47);
  }
  for (const x of [-19.2, 19.2]) for (const y of [32.6, 43.4]) {
    cylinder(parts.radar, 1.1, 1.8, gold, 'Radar_hole_illustration', x, y, 8.6);
  }
  box(parts.radar, 9, 4.5, 3.3, dark, 'Radar_connector_placeholder', 14, 34.5, 6);

  plate(parts.esp32, 28, 54, 1.9, 1.6, blackBoard, 'ESP32_board_assumed_28x54', -17, -6, -3);
  box(parts.esp32, 17.5, 19.5, 2.4, metal, 'ESP32_RF_shield', -17, -3, -0.95);
  box(parts.esp32, 16, 5.5, 2.8, dark, 'ESP32_controller', -17, -18, -1);
  box(parts.esp32, 8.6, 5.0, 4.7, metal, 'ESP32_USB_placeholder', -17, -31.3, -1);
  for (let i = 0; i < 13; i++) for (const x of [-29.5, -4.5]) {
    box(parts.esp32, 1.2, 1.2, 2.8, gold, 'Header_pin', x, -28 + i * 3.5, -1.0);
  }
  for (let i = 0; i < 4; i++) {
    box(parts.esp32, 3.7, 0.7, 0.15, gold, 'Wifi_trace_horizontal', -22.7 + i * 3.5, 15.9 - (i % 2) * 4.5, -2.1);
    box(parts.esp32, 0.7, 5.1, 0.15, gold, 'Wifi_trace_vertical', -24.2 + i * 3.5, 13.7, -2.1);
  }
  for (const x of [-27, -7]) for (const y of [-29, 17]) {
    cylinder(parts.esp32, 1.4, 1.8, gold, 'ESP32_mount_illustration', x, y, -3);
  }

  plate(parts.battery, 35, 55, 3, 6, amber, 'LiPo_pouch_assumed_35x55x6', 8, -21.5, -14);
  plate(parts.battery, 32.5, 48.7, 2.2, 0.3, foil, 'LiPo_foil_face', 8, -22, -10.8);
  box(parts.battery, 36.5, 2.2, 7.2, charcoal, 'Battery_cradle_lower_pad', 8, -50.3, -14);
  box(parts.battery, 2, 48, 7, charcoal, 'Battery_cradle_side_pad', 27.5, -22, -14);
  // Deliberately generic: this volume does not pretend to be a validated power PCB.
  plate(parts.power, 46, 22, 2, 1.6, boardMaterial, 'Power_board_reserved_envelope', 0, -42, 11);
  box(parts.power, 13, 11, 5.5, dark, 'Power_module_reserved_component', -10, -42, 14);
  cylinder(parts.power, 4.5, 4, metal, 'Power_module_reserved_capacitor', 6, -42, 14);
  box(parts.power, 5.5, 6, 4, polymer, 'Battery_connector_placeholder', 17, -40, 14);
  const wireMaterial = standard(0xc96e50, 0.7);
  const wireCurve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(17, -40, 16), new THREE.Vector3(27, -30, 15),
    new THREE.Vector3(29, -9, 1), new THREE.Vector3(20, 4.5, -11)
  ]);
  mesh(parts.power, new THREE.TubeGeometry(wireCurve, 28, 0.55, 8, false), wireMaterial, 'Illustrative_battery_lead');

  const dimensionRoot = new THREE.Group(); scene.add(dimensionRoot);
  function label(text, position, className, parent = dimensionRoot) {
    const element = document.createElement('span'); element.className = className; element.textContent = text;
    const object = new CSS2DObject(element); object.position.copy(position); parent.add(object);
    return object;
  }
  function dimension(a, b, text, labelPosition) {
    const geometry = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(...a), new THREE.Vector3(...b)]);
    const line = new THREE.Line(geometry, new THREE.LineBasicMaterial({ color: 0x8a987f }));
    dimensionRoot.add(line);
    const points = [a, b];
    for (const p of points) {
      const tick = new THREE.Line(new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(p[0] - 1.8, p[1] + 1.8, p[2]),
        new THREE.Vector3(p[0] + 1.8, p[1] - 1.8, p[2])
      ]), new THREE.LineBasicMaterial({ color: 0x8a987f }));
      dimensionRoot.add(tick);
    }
    label(text, new THREE.Vector3(...labelPosition), 'dimension-label');
  }
  dimension([-39, -66, 27], [39, -66, 27], '78 mm', [0, -68, 29]);
  dimension([-51, -56, 24], [-51, 56, 24], '112 mm', [-57, 0, 24]);
  dimension([48, -60, -23], [48, -60, 23], '46 mm', [54, -62, 0]);
  const partLabels = {};
  for (const [id, text, xyz] of [
    ['radar', '02 / LD2450', [0, 50, 10]],
    ['esp32', '03 / ESP32', [-30, -7, 4]],
    ['battery', '04 / LiPo 1S', [36, -20, -10]],
    ['power', '05 / POWER BAY', [2, -57, 16]]
  ]) {
    partLabels[id] = label(text, new THREE.Vector3(...xyz), 'part-label', parts[id]);
  }
  const selection = new THREE.BoxHelper(parts.front, 0xaec56c);
  selection.visible = false; selection.material.transparent = true; selection.material.opacity = 0.65;
  scene.add(selection);

  let mode = 'assembled', selected = 'front', amount = 0, target = 0, frame = 0;
  const offsets = {
    shell: [18, 0, 0], rear: [48, 0, -65], battery: [45, 0, -32],
    esp32: [-20, 0, 25], power: [-10, -1, 25], radar: [0, 18, 38], front: [-78, 0, 60]
  };
  const shellMaterials = new Set();
  parts.shell.traverse(object => {
    if (!object.isMesh) return;
    object.material = object.material.clone();
    shellMaterials.add(object.material);
  });
  function applyPose() {
    for (const [id, part] of Object.entries(parts)) part.position.fromArray(offsets[id]).multiplyScalar(amount);
    parts.front.visible = mode !== 'cutaway';
    for (const material of shellMaterials) {
      material.transparent = mode !== 'assembled';
      material.opacity = mode === 'cutaway' ? 0.15 : mode === 'exploded' ? 0.5 : 1;
      material.depthWrite = mode === 'assembled';
    }
    for (const object of Object.values(partLabels)) object.visible = mode !== 'assembled';
    selection.visible = mode !== 'assembled' && parts[selected].visible;
    if (selection.visible) selection.setFromObject(parts[selected]);
  }
  function requestRender() { if (!frame) frame = requestAnimationFrame(render); }
  function render() {
    frame = 0;
    const moving = Math.abs(amount - target) > 0.001;
    amount = reducedMotion || !moving ? target : THREE.MathUtils.lerp(amount, target, 0.16);
    applyPose();
    const changed = controls.update();
    renderer.render(scene, camera); labels.render(scene, camera);
    if (moving || controls.autoRotate || changed) requestRender();
  }
  controls.addEventListener('change', requestRender);
  function resize() {
    const width = stage.clientWidth, height = stage.clientHeight;
    if (!width || !height) return;
    renderer.setSize(width, height, false); labels.setSize(width, height);
    camera.aspect = width / height; camera.updateProjectionMatrix(); requestRender();
  }
  new ResizeObserver(resize).observe(stage);
  function setView(view = 'iso') {
    const positions = {
      iso: mode === 'exploded' ? [300, 148, 416] : [175, 105, 230],
      front: [0, 0, 300], side: [300, 0, 0.1], back: [0, 0, -300]
    };
    camera.position.fromArray(positions[view] || positions.iso);
    controls.target.set(0, 0, mode === 'exploded' ? 12 : 0);
    controls.update(); requestRender();
  }
  function setMode(next, updateSlider = true) {
    mode = next;
    target = next === 'exploded' ? 1 : 0;
    for (const button of document.querySelectorAll('[data-mode]')) {
      const active = button.dataset.mode === next;
      button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
    }
    if (updateSlider) {
      document.getElementById('explode').value = String(target * 100);
      document.getElementById('explode-value').value = String(target * 100) + '%';
    }
    dimensionRoot.visible = document.getElementById('dimensions').checked && next !== 'exploded';
    selection.visible = next !== 'assembled';
    setView('iso'); requestRender();
  }
  for (const button of document.querySelectorAll('[data-mode]')) button.addEventListener('click', () => setMode(button.dataset.mode));
  for (const button of document.querySelectorAll('[data-view]')) button.addEventListener('click', () => setView(button.dataset.view));
  selectPart3D = id => {
    selected = id;
    if (mode === 'assembled' && selected !== 'front') setMode('cutaway');
    selection.visible = mode !== 'assembled'; requestRender();
  };
  document.getElementById('explode').addEventListener('input', event => {
    const value = Number(event.target.value);
    if (mode !== 'exploded') setMode('exploded', false);
    target = value / 100;
    document.getElementById('explode-value').value = String(value) + '%'; requestRender();
  });
  document.getElementById('dimensions').addEventListener('change', event => {
    dimensionRoot.visible = event.target.checked && mode !== 'exploded'; requestRender();
  });
  document.getElementById('spin').addEventListener('change', event => {
    controls.autoRotate = event.target.checked; requestRender();
  });
  document.getElementById('reset').addEventListener('click', () => {
    controls.autoRotate = false; document.getElementById('spin').checked = false;
    setMode('assembled'); setView('iso');
  });
  function download(blob, name) {
    const url = URL.createObjectURL(blob), link = document.createElement('a');
    link.href = url; link.download = name; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }
  document.getElementById('save-image').addEventListener('click', () => {
    renderer.render(scene, camera);
    renderer.domElement.toBlob(blob => {
      if (!blob) { downloadStatus.textContent = 'เบราว์เซอร์บันทึกภาพไม่ได้'; return; }
      download(blob, 'human-radar-concept-01.png');
      downloadStatus.textContent = 'บันทึกภาพโมเดลแล้ว (ไม่รวมป้าย HTML)';
    }, 'image/png');
  });
  document.getElementById('export-model').addEventListener('click', async event => {
    const button = event.currentTarget; button.disabled = true;
    downloadStatus.textContent = 'กำลังเตรียมโมเดลประกอบ…';
    try {
      const clone = assembly.clone(true);
      clone.position.set(0, 0, 0); clone.rotation.set(0, 0, 0); clone.scale.setScalar(0.001);
      const uiLabels = [];
      clone.traverse(object => { if (object.isCSS2DObject) uiLabels.push(object); });
      for (const object of uiLabels) object.removeFromParent();
      for (const part of clone.children) { part.position.set(0, 0, 0); part.visible = true; }
      clone.traverse(object => {
        if (!object.isMesh) return;
        object.material = object.material.clone();
        object.material.opacity = object.userData.baseOpacity;
        object.material.transparent = object.userData.baseTransparent;
        object.material.depthWrite = !object.material.transparent;
      });
      const result = await new GLTFExporter().parseAsync(clone, { binary: true, onlyVisible: true });
      download(new Blob([result], { type: 'model/gltf-binary' }), 'human-radar-concept-01.glb');
      downloadStatus.textContent = 'บันทึก GLB แล้ว · โมเดลร่าง หน่วยเมตร ไม่ใช่ไฟล์พร้อมผลิต';
    } catch (error) {
      console.error('Concept export failed', error);
      downloadStatus.textContent = 'ส่งออกไม่สำเร็จ ลองบันทึกภาพ 3D แทนได้ครับ';
    } finally { button.disabled = false; }
  });
  setView('iso'); resize(); requestRender();
  status.textContent = '3D READY · CONCEPT ONLY';
  stage.dataset.ready = 'true';
  // Small inspection surface for browser QA; carries no device/network credentials.
  window.conceptViewer = {
    get mode() { return mode; },
    get explosion() { return amount; },
    get renderCalls() { return renderer.info.render.calls; },
    get meshCount() { let n = 0; assembly.traverse(o => { if (o.isMesh) n++; }); return n; }
  };
}

try { start(); }
catch (error) {
  selectPart3D = () => {};
  console.error('WebGL concept viewer unavailable', error);
  status.textContent = 'ภาพคอนเซปต์ / 3D ไม่พร้อม';
  document.getElementById('webgl-fallback').hidden = false;
  for (const control of document.querySelectorAll('[data-mode],[data-view],#export-model,#save-image,#reset,#explode,#dimensions,#spin')) control.disabled = true;
}
