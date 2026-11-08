# Human Radar enclosure — Concept 01

This is a visual packaging study, not a manufacturing design. The enclosure envelope is 78 W × 112 H × 46 D mm. ESP32, battery, charging/power bay, connector and fastener dimensions are placeholders.

## Deliverables

- index.html: offline-capable interactive 3D viewer; open directly in a browser.
- viewer-source.js: editable procedural geometry and interaction source.
- viewer.bundle.js: self-contained classic-script build, including Three.js r170 and addons.
- human-radar-concept-01.glb: assembled concept model; glTF units are metres.
- assets/concept-engineering.png: AI-generated assembled/exploded design board.
- generation-prompt.txt: exact built-in imagegen prompt.

The viewer supports assembled/cutaway/exploded states, orbit, zoom, camera presets, part notes, dimension labels, an explosion slider, image download and assembled GLB export. On a browser without WebGL, the illustration and textual part notes still work; 3D-only controls are disabled.

## Assumptions

LD2450's 44 × 15 mm outline and the nominal 12.4 mm antenna-to-cover spacing follow the manufacturer's manual:

https://h.hlktech.com/download/HLK-LD2450-24G/1/HLK%20LD2450%201T2R%E8%BF%90%E5%8A%A8%E7%9B%AE%E6%A0%87%E6%A3%80%E6%B5%8B%E8%BF%BD%E8%B8%AA%E6%A8%A1%E7%BB%84%E8%AF%B4%E6%98%8E%E4%B9%A6%20V1.02%20.pdf

The 1.2 mm RF window is provisional. Actual material, assembly gaps and RF response require testing. Battery clearance, mounting, charging-port geometry, thermal behavior and wiring need the real parts. The image is an artistic interpretation and may differ from the procedural model. This is not STL/STEP CAD and has no claimed water, impact or RF qualification.

## Rebuild

Use Node.js and install three@0.170.0 plus esbuild@0.24.2 into a temporary build directory. Bundle viewer-source.js with esbuild using bundle=true, format=iife, target=es2020, minify=true, legalComments=eof and that directory's node_modules in nodePaths. Keep assets/THREE-LICENSE.txt with any redistribution. Runtime has no CDN or font dependency.

## Browser validation

Own headless Chrome with software WebGL: local-file rendering, 89 meshes, assembled/cutaway/exploded views, physical mouse orbit, keyboard slider, selections, dimensions/spin/reset, PNG and GLB downloads. Mobile widths 390 and 320 px checked for horizontal overflow. A forced WebGL context failure checks that textual notes remain functional and 3D-only controls disable. The GLB header, concept metadata, mesh count and mm-to-metre root transform are checked.

## Hosting

Static copy: /var/www/human-radar-concepts/enclosure-v1/.
Nginx include: deploy/nginx-human-radar-concepts.conf in the existing HTTPS server block. The static location inherits normal HTML/JS/CSS/image MIME types.

No Android release or firmware/device changes are part of this concept task.

Published preview: https://systemdeveloper.ddns.net/human-radar/concepts/enclosure-v1/

Public HTTPS verification passed for HTML/JavaScript/CSS/PNG/GLB bytes and MIME types; the existing hostname's original site still returns 200. Mobile-context Chrome verifies touch orbit, exploded mode, loaded image, identical static GLB download and no JS errors/overflow. Nginx reload was scoped to the new static prefix.
