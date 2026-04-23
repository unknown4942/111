// ==UserScript==
// @name         Devast.io Admin Pro
// @namespace    devast-admin-tp
// @version      3.0.0
// @match        *://devast.io/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

// Erken WebSocket hook — [2] paketini kaçırmamak için
// (function() closure'dan önce çalışır)
(function() {
  const _earlyWS = window.WebSocket;
  const _earlyMessages = [];
  window.WebSocket = function(url, p) {
    const ws = p ? new _earlyWS(url, p) : new _earlyWS(url);
    if (typeof url === "string" && url.includes("devast")) {
      const _origAEL = EventTarget.prototype.addEventListener;
      _origAEL.call(ws, "message", function(e) {
        if (typeof e.data === "string") {
          try {
            const arr = JSON.parse(e.data);
            if (arr[0] === 2) {
              window._earlyPkt2 = arr;
            } else if (arr[0] === 1) {
              if (!window._earlyPkt1) window._earlyPkt1 = [];
              window._earlyPkt1.push(arr);
            }
          } catch(_) {}
        }
      });
    }
    return ws;
  };
  window.WebSocket.prototype = _earlyWS.prototype;
  window.WebSocket.CONNECTING = _earlyWS.CONNECTING;
  window.WebSocket.OPEN = _earlyWS.OPEN;
  window.WebSocket.CLOSING = _earlyWS.CLOSING;
  window.WebSocket.CLOSED = _earlyWS.CLOSED;
})();

(function () {
  "use strict";

  // ─── Orijinal sabitler (değiştirilmedi) ───────────────────────────────────
  const CANVAS_ID        = "can";
  const TELEPORT_PREFIX  = "!teleport=";
  const PLAYER_ID_PACKET = 52;
  const POSITION_PACKET  = 37;
  const ENTITY_SIZE      = 20;
  const TILE_SIZE        = 100;
  const MAX_TILE         = 149;

  const ZOOM_STEP        = 0.1;
  const MIN_ZOOM_OFFSET  = -0.67;  // Oyunun -0.35 limitini aştık
  const MAX_ZOOM_OFFSET  = 2;      // Zoom in limitini genişlettik
  const ZOOM_LERP        = 0.01;
  const POSITION_LERP    = 0.1;
  const CAMERA_LERP      = 0.025;
  const CAMERA_RADIUS    = 100;

  // ─── YENİ: Mod sistemi ────────────────────────────────────────────────────
  // Mod 0 = Normal, Mod 1 = Mouse Teleporter, Mod 2 = Mouse Selector
  // Orijinalde sadece enabled/disabled vardı, bunu 3 moda çevirdik
  const MODES = ["Normal", "Mouse Teleporter", "Mouse Selector"];
  // Selector alt modları: 0 = Build (!build), 1 = Spawn AI (!spawn-AI)
  // C tuşu ile selector içinde geçiş yapılır
  const SELECTOR_SUB_MODES = ["Build", "Spawn AI"];
  let mode = 0;
  let selectorSubMode = 0;
  let buildBlockName = "dynamite";   // !block-name= ile değişir
  let spawnAiName    = "armored_ghoul"; // !ai-name= ile değişir

  // ─── YENİ: Short commands tablosu ────────────────────────────────────────
  // i=alias, i+1=expansion şeklinde çift eleman
  const SHORT_COMMANDS = [
    "tp=",        "teleport=",
    "it=",        "item=",
    "tpt",        "teleport-to",
    "tpm",        "teleport-to-me",
    "!k=",        "!kick=",
    "ask",        "add-starter-kit",
    "csk",        "clean-starter-kit",
    "gfd",        "gauge-food-decrease",
    "gcd",        "gauge-cold-decrease",
    "gci",        "gauge-cold-increase",
    "gsd",        "gauge-stamina-decrease",
    "gsi",        "gauge-stamina-increase",
    "grd",        "gauge-rad-decrease",
    "gri",        "gauge-rad-increase",
    "gld",        "gauge-life-decrease",
    "gli",        "gauge-life-increase",
    "builder",    "!item=steel_bigdoor!item=steel_bigdoor!item=steel_bigdoor!item=steel_wall!item=steel_smallwall!item=stone_floor!item=stone_floor!item=stone_floor!item=stone_floor",
    "sup",        "item=super_hammer",
    "kebab",      "immortal-on",
    "nemo",       "immortal-off",
    "ia",         "item-all",
    "cellss",     "item=energy_cells!item=energy_cells!item=energy_cells!item=energy_cells",
    "ammo",       "!item=bullet_sniper!item=bullet_sniper!item=bullet_sniper!item=bullet_sniper",
    "bags",       "!item=sleeping_bag",
    "base",       "!item=workbench2!item=sleeping_bag!item=fridge!item=tesla",
    "stones",     "!item=stone_bigdoor!item=stone_bigdoor!item=stone_wall!item=stone_floor!item=stone_floor!item=stone_floor",
    "boom",       "!item=dynamite!item=grenade!item=grenade!item=grenade!item=c4!item=armor_deminer!item=hammer",
    "tesla_bots", "!item=tesla_bot!item=tesla_bot!item=tesla_bot!item=tesla_bot!item=tesla_bot!item=tesla_bot!item=tesla_bot",
    "kit",        "!item=sulfur_axe!item=sulfur_pickaxe!item=workbench2*10!item=electronics!item=shaped_uranium!item=alloys!item=shaped_metal!item=wire",
    "fg",         "!item=armor_tesla_2!item=armor_fire_3!item=ak47!item=Laser_Submachine!item=bullet_sniper!item=energy_cells",
    "nades",      "item=grenade!item=grenade!item=dynamite",
  ];

  // vA9 intercept — oyunun global player name array'ini yakalamaya çalış
  // Oyun global scope'ta tanımlarsa bunu periyodik olarak okuruz
  function tryReadGamePlayerArray() {
    try {
      // Oyun vA9 adında global bir dizi kullanıyor
      const gameArr = window.vA9;
      if (Array.isArray(gameArr) && gameArr.length > 1) {
        for (let i = 1; i < gameArr.length - 1; i++) {
          const val = gameArr[i];
          if (typeof val === "string" && val.length > 0) {
            const p    = knownPlayers.get(i) || { id: i, x: 0, y: 0 };
            p.name     = val;
            p.joinTime = p.joinTime || Date.now();
            p.score    = p.score || 0;
            p.clan     = p.clan || "";
            knownPlayers.set(i, p);
          }
        }
      }
    } catch (_) {}
  }
  // Her 2 saniyede bir kontrol et
  setInterval(tryReadGamePlayerArray, 2000);

  // ─── Orijinal state değişkenleri (aynen korundu) ─────────────────────────
  let socket         = null;
  let sendSocketData = null;
  let xorKey         = null;
  let playerId       = null;

  let hasPlayerPosition = false;
  let playerX = 0, playerY = 0;
  let playerXTarget = 0, playerYTarget = 0;

  let mouseX = window.innerWidth / 2;
  let mouseY = window.innerHeight / 2;

  let panX = 0, panY = 0;
  let baseScale = 1, zoomTarget = 0, zoomValue = 0;
  let lastFrameTime = 0;
  let wheelCanvas   = null;
  let _realScale    = 0; // setTransform hook'tan gelen gerçek oyun scale'i
  let _lockedScale      = false;
  let _lockedScaleValue = 0;

  // Zoom lock wheel engeli — oyundan önce çalışır
  window._zoomLocked = false;
  window.addEventListener("wheel", function(e) {
    if (window._zoomLocked) {
      e.stopImmediatePropagation();
      e.preventDefault();
    }
  }, { capture: true, passive: false });

  // setTransform hook — oyunun gerçek render scale + offset'ini yakala
  let _realOffX = 0, _realOffY = 0;
  (function(){
    const _orig = CanvasRenderingContext2D.prototype.setTransform;
    CanvasRenderingContext2D.prototype.setTransform = function(a,b,c,d,e,f) {
      if (typeof a === "number" && a > 0.1 && b === 0 && c === 0 && d === a) {
        _realScale = a;
        if (typeof e === "number" && typeof f === "number") {
          _realOffX = e;
          _realOffY = f;
        }
      }
      return _orig.apply(this, arguments);
    };
  })();

  // fillText hook — oyunun canvas'a yazdığı nickname#id formatını yakala
  (function(){
    const _origFill = CanvasRenderingContext2D.prototype.fillText;
    CanvasRenderingContext2D.prototype.fillText = function(text) {
      // nickname#id formatı: "PlayerName#42"
      if (typeof text === "string") {
        const m = text.match(/^(.+)#(\d+)$/);
        if (m) {
          const name = m[1];
          const id   = parseInt(m[2]);
          if (id > 0 && id < 200 && name.length > 0) {
            const p = knownPlayers.get(id) || { id, x: 0, y: 0 };
            if (!p.name) {
              p.name     = name;
              p.joinTime = p.joinTime || Date.now();
              p.score    = p.score    || 0;
              p.clan     = p.clan     || "";
              knownPlayers.set(id, p);
            }
          }
        }
      }
      return _origFill.apply(this, arguments);
    };
  })();

  // ─── YENİ: Ek state ───────────────────────────────────────────────────────
  let showPlayerList  = false;
  let showMouseCoords = false;
  let showMiniMap     = false;  // M tuşu
  let showCheckpoints = false;  // Ctrl+G listesi
  const checkpoints   = JSON.parse(localStorage.getItem("_checkpoints") || "[]");
  let selectorActive  = false;  // sağ tık basılı mı
  let selectorStartTX = 0, selectorStartTY = 0; // başlangıç tile
  const knownPlayers  = new Map(); // player list için: id → {id, x, y}

  // Status element (orijinaldekini koruyoruz, mod adını da gösterecek şekilde)
  let statusElement = null;

  // ─── Orijinal yardımcı fonksiyonlar (değiştirilmedi) ─────────────────────
  function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
  }

  function readUint16(buffer, offset) {
    return buffer[offset] | (buffer[offset + 1] << 8);
  }

  function updateBaseScale() {
    baseScale = Math.max(window.innerHeight / 880, window.innerWidth / 1280);
  }

  function getScale() {
    const pr = window.devicePixelRatio || 1;
    // Zoom lock aktifse sabit scale kullan
    if (window._zoomLocked && _realScale > 0.1) {
      return (_realScale / pr);
    }
    if (_realScale > 0.1) {
      return (_realScale / pr) * (1 + zoomValue);
    }
    return baseScale * (1 + zoomValue);
  }



  function resetState() {
    xorKey = null;
    playerId = null;
    hasPlayerPosition = false;
    playerX = 0; playerY = 0;
    playerXTarget = 0; playerYTarget = 0;
    panX = 0; panY = 0;
    zoomTarget = 0; zoomValue = 0;
    knownPlayers.clear();
    // Erken yakalanan [2] paketini işle
    setTimeout(() => {
      if (window._earlyPkt2) {
        handleJsonMessage(JSON.stringify(window._earlyPkt2));
      }
      if (window._earlyPkt1) {
        window._earlyPkt1.forEach(arr => handleJsonMessage(JSON.stringify(arr)));
      }
    }, 500);
  }

  function guessPacketKeys(packet) {
    const entityCount = Math.floor((packet.length - 2) / ENTITY_SIZE);
    if (entityCount <= 0) return [0];
    const keys = [];
    for (let key = 0; key < 256; key++) {
      if ((packet[1] ^ key) > 1) continue;
      let valid = true;
      for (let entityIndex = 0; entityIndex < Math.min(entityCount, 10); entityIndex++) {
        const markerOffset = 2 + entityIndex * ENTITY_SIZE + 3;
        if ((packet[markerOffset] ^ key) > 200) { valid = false; break; }
      }
      if (valid) keys.push(key);
    }
    return keys.length > 0 ? keys : [0];
  }

  // handlePositionPacket: orijinal mantık korundu + player list için ek parse
  function handlePositionPacket(data) {
    const packet = new Uint8Array(data);
    if (packet.length < ENTITY_SIZE + 2 || playerId === null) return;

    const keys = xorKey !== null ? [xorKey] : guessPacketKeys(packet);

    for (const key of keys) {
      const decoded = new Uint8Array(packet);
      if (key !== 0) {
        for (let index = 1; index < decoded.length; index++) {
          decoded[index] ^= key;
        }
      }

      const entityCount = Math.floor((decoded.length - 2) / ENTITY_SIZE);

      for (let entityIndex = 0, offset = 2; entityIndex < entityCount; entityIndex++, offset += ENTITY_SIZE) {
        const entityId   = decoded[offset];
        const entityKind = decoded[offset + 3];

        // YENİ: player list için tüm oyuncuları kaydet (kind===0)
        if (entityKind === 0) {
          const ex = readUint16(decoded, offset + 12);
          const ey = readUint16(decoded, offset + 14);
          const existing = knownPlayers.get(entityId) || { id: entityId };
          existing.x = ex;
          existing.y = ey;
          existing._lastSeen = Date.now();
          knownPlayers.set(entityId, existing);
        }

        // Orijinal: kendi pozisyonumuzu bul
        if (entityKind !== 0 || entityId !== playerId) continue;

        if (xorKey === null) xorKey = key;

        playerXTarget = readUint16(decoded, offset + 12);
        playerYTarget = readUint16(decoded, offset + 14);

        if (!hasPlayerPosition) {
          playerX = playerXTarget;
          playerY = playerYTarget;
          hasPlayerPosition = true;
        }

        return;
      }
    }
  }

  function handleSocketMessage(event) {
    // Binary mesaj
    if (event.data instanceof ArrayBuffer) {
      const bytes = new Uint8Array(event.data);
      // DEBUG: ilk byte'ı logla
      if (bytes[0] === PLAYER_ID_PACKET && bytes.length > 1) {
        playerId = bytes[1];
        xorKey = null;
        hasPlayerPosition = false;
        knownPlayers.clear();
        return;
      }
      if (bytes[0] === POSITION_PACKET) handlePositionPacket(event.data);
      return;
    }

    // String mesaj — JSON dene
    if (typeof event.data === "string") {
      handleJsonMessage(event.data);
      return;
    }

    // Blob mesaj
    if (event.data instanceof Blob) {
      event.data.arrayBuffer().then(buf => {
        const bytes = new Uint8Array(buf);
      });
      event.data.text().then(text => {
        if (text[0] === '[' || text[0] === '{') {
          try { handleJsonMessage(text); } catch(_) {}
        }
      });
    }
  }

  // JSON paketlerinden oyuncu bilgilerini çek
  // Packet type 0 → score update:  [0, id, score]
  // Packet type 1 → player join:   [1, id, ?, nickname, clan_tag, ...]
  // Packet type 2 → full list:     [2, "name0", "name1", ...] (index=id)
  const serverStartTime = Date.now();

  function handleJsonMessage(data) {
    try {
      const arr = JSON.parse(data);
      if (!Array.isArray(arr)) return;

      // DEBUG

      if (arr[0] === 1 && arr.length >= 4) {
        // Player join: [1, id, ?, nickname, clan_tag, ...]
        const id   = arr[1];
        const p    = knownPlayers.get(id) || { id, x: 0, y: 0 };
        p.name     = arr[3] ? String(arr[3]) : "";
        p.clan     = (typeof arr[4] === "string" && arr[4].length > 0) ? arr[4] : "";
        p.joinTime = Date.now();
        p.score    = p.score || 0;
        knownPlayers.set(id, p);

      } else if (arr[0] === 2 && arr.length > 1) {
        // Full name list: [2, name_id1, name_id2, ..., token]
        // Son eleman token (length-1), ilk eleman type=2
        // arr[i] string ise oyuncu var, 0 veya "" ise yok
        for (let i = 1; i < arr.length - 1; i++) {
          const val = arr[i];
          if (typeof val === "string" && val.length > 0) {
            const p    = knownPlayers.get(i) || { id: i, x: 0, y: 0 };
            p.name     = val;
            p.joinTime = p.joinTime || Date.now();
            p.score    = p.score || 0;
            p.clan     = p.clan || "";
            knownPlayers.set(i, p);
          // Boş slot — dokunma
          }
        }

      } else if (arr[0] === 0 && arr.length >= 3) {
        // Score update: [0, id, score]
        const id = arr[1];
        const p  = knownPlayers.get(id) || { id, x: 0, y: 0 };
        p.score  = arr[2];
        knownPlayers.set(id, p);

      } else if (arr[0] === 5 && arr.length > 1) {
        // [5] = oyuncu array init — tam listeyi yeniden yükle
        // arr içinde oyuncu nesneleri var
        // Şimdilik pas geç
      }
    } catch (_) {}
  }

  function formatScore(score) {
    if (!score) return "0";
    if (score >= 1000000) return (score / 1000000).toFixed(1) + "M";
    if (score >= 1000)    return Math.floor(score / 1000) + "k";
    return String(score);
  }

  function formatTime(joinTime) {
    if (!joinTime) return "00:00";
    const secs  = Math.floor((Date.now() - joinTime) / 1000);
    const mins  = Math.floor(secs / 60);
    const s     = secs % 60;
    return (mins < 10 ? "0" + mins : mins) + ":" + (s < 10 ? "0" + s : s);
  }

  function sendPacket(payload) {
    if (!socket || socket.readyState !== 1 || !sendSocketData) return;
    sendSocketData.call(socket, JSON.stringify(payload));
  }

  // ─── Discord Bot: Chat komut dinleyici ────────────────────────────────────
  // Webhook formatı:
  // Title: "Chat Message"
  // Description: "fg?"
  // Fields: Player = "Rawr#6", ID = "6"
  const DC_ITEM_CMDS = {
    "fg":    (id) => [`!item-to=${id}::armor_tesla_2`,`!item-to=${id}::armor_fire_3`,`!item-to=${id}::ak47`,`!item-to=${id}::Laser_Submachine`,`!item-to=${id}::bullet_sniper`,`!item-to=${id}::energy_cells`],
    "boom":  (id) => [`!item-to=${id}::dynamite`,`!item-to=${id}::grenade`,`!item-to=${id}::grenade`,`!item-to=${id}::grenade`,`!item-to=${id}::c4`,`!item-to=${id}::armor_deminer`],
    "bk":    (id) => [`!item-to=${id}::steel_bigdoor`,`!item-to=${id}::steel_bigdoor`,`!item-to=${id}::steel_bigdoor`,`!item-to=${id}::steel_wall`,`!item-to=${id}::steel_smallwall`,`!item-to=${id}::stone_floor`,`!item-to=${id}::stone_floor`],
    "ammo":  (id) => [`!item-to=${id}::bullet_sniper`,`!item-to=${id}::bullet_sniper`,`!item-to=${id}::bullet_sniper`,`!item-to=${id}::bullet_sniper`],
    "cellss":(id) => [`!item-to=${id}::energy_cells`,`!item-to=${id}::energy_cells`,`!item-to=${id}::energy_cells`,`!item-to=${id}::energy_cells`],
  };

  // Etkin komutlar seti — event modunda hangi komutlar çalışsın
  let _dcEnabled    = false;
  let _dcInterval   = null;
  let _dcLastMsgId  = null;
  const _dcToken    = { v: localStorage.getItem("_dc_token")   || "" };
  const _dcChannel  = { v: localStorage.getItem("_dc_channel") || "" };

  async function _dcPoll() {
    if (!_dcToken.v || !_dcChannel.v) return;
    try {
      const res = await fetch(
        "https://discord.com/api/v10/channels/" + _dcChannel.v + "/messages?limit=5",
        { headers: { "Authorization": "Bot " + _dcToken.v } }
      );
      if (!res.ok) return;
      const msgs = await res.json();
      if (!msgs.length) return;

      const newMsgs = _dcLastMsgId
        ? msgs.filter(m => BigInt(m.id) > BigInt(_dcLastMsgId))
        : [];
      _dcLastMsgId = msgs[0].id;

      for (const msg of newMsgs) {
        // Embed formatı: title="Chat Message", fields: Player, ID
        const embeds = msg.embeds || [];
        for (const embed of embeds) {
          if (!embed.title || !embed.title.includes("Chat Message")) continue;
          const msgText = (embed.description || "").trim().toLowerCase().replace(/[?!]/g, "");
          const idField = (embed.fields || []).find(f => f.name === "ID");
          if (!idField) continue;
          const playerId = parseInt(idField.value);
          if (!playerId) continue;
          const handler = DC_ITEM_CMDS[msgText];
          if (!handler) continue;
          // Item ver
          handler(playerId).forEach(cmd => sendPacket([1, cmd]));
        }
      }
    } catch(_) {}
  }

  function _dcStart() {
    if (_dcInterval) return;
    _dcLastMsgId = null;
    // İlk poll — sadece son mesaj ID'yi kaydet, işlem yapma
    fetch(
      "https://discord.com/api/v10/channels/" + _dcChannel.v + "/messages?limit=1",
      { headers: { "Authorization": "Bot " + _dcToken.v } }
    ).then(r => r.json()).then(msgs => {
      if (msgs.length) _dcLastMsgId = msgs[0].id;
    }).catch(() => {});
    _dcInterval = setInterval(_dcPoll, 3000);
    _dcEnabled = true;
  }

  function _dcStop() {
    clearInterval(_dcInterval);
    _dcInterval = null;
    _dcEnabled = false;
  }

  // ─── Status element: mod adını gösterecek şekilde güncellendi ────────────
  function ensureStatusElement() {
    if (statusElement) return statusElement;
    statusElement = document.createElement("div");
    statusElement.style.cssText = [
      "position:fixed", "top:8px", "left:50%",
      "transform:translateX(-50%)", "z-index:99999",
      "padding:4px 16px", "border-radius:4px",
      "font:bold 13px monospace", "pointer-events:none", "color:#fff"
    ].join(";");
    statusElement.style.display = "none";
    const mount = () => {
      if (document.body && statusElement && !statusElement.isConnected)
        document.body.appendChild(statusElement);
    };
    document.body ? mount() : window.addEventListener("DOMContentLoaded", mount, { once: true });
    return statusElement;
  }

  const MODE_COLORS = [
    "rgba(60,60,60,0.85)",
    "rgba(0,180,60,0.85)",
    "rgba(0,100,210,0.85)",
  ];

  function updateStatus() {
    const element = ensureStatusElement();
    clearTimeout(element._hideTimer);
    // Selector modundayken alt modu da göster
    const label = mode === 2
      ? `Mouse Selector — ${SELECTOR_SUB_MODES[selectorSubMode]}`
      : MODES[mode];
    element.textContent = label;
    element.style.display = "block";
    element.style.background = MODE_COLORS[mode];
    element._hideTimer = window.setTimeout(() => {
      element.style.display = "none";
    }, 2000);
  }

  // ─── Orijinal wheel ve pan (değiştirilmedi) ───────────────────────────────
  function onCanvasWheel(event) {
    // Zoom sabit tutuldu — teleport isabeti için
    // zoomTarget += event.deltaY < 0 ? ZOOM_STEP : -ZOOM_STEP;
    // zoomTarget = clamp(zoomTarget, MIN_ZOOM_OFFSET, MAX_ZOOM_OFFSET);
  }

  function ensureCanvasWheelListener() {
    const canvas = document.getElementById(CANVAS_ID);
    if (!canvas || canvas === wheelCanvas) return;
    wheelCanvas = canvas;
    wheelCanvas.addEventListener("wheel", onCanvasWheel, { capture: true, passive: true });
  }

  function updateMousePan() {
    const centerX = window.innerWidth / 2;
    const centerY = window.innerHeight / 2;
    const offsetX = mouseX - centerX;
    const offsetY = mouseY - centerY;
    const distance = Math.sqrt(offsetX * offsetX + offsetY * offsetY);
    const deadZone = Math.min(window.innerWidth / 4, window.innerHeight / 4);
    let panDistance = 0;
    if (distance > deadZone) {
      panDistance = CAMERA_RADIUS * Math.min((distance - deadZone) / deadZone, 1);
    }
    const angle = Math.atan2(offsetY, offsetX);
    panX += (Math.sin(angle) * panDistance - panX) * CAMERA_LERP;
    panY += (Math.cos(angle) * panDistance - panY) * CAMERA_LERP;
  }

  // ─── Orijinal koordinat dönüşümleri (değiştirilmedi) ─────────────────────
  function screenToWorld(screenX, screenY) {
    const scale = getScale();
    const centerX = window.innerWidth / 2;
    const centerY = window.innerHeight / 2;
    return {
      x: playerX + panX + (screenX - centerX) / scale,
      y: playerY + panY + (screenY - centerY) / scale
    };
  }

  function worldToScreen(worldX, worldY) {
    const scale = getScale();
    const centerX = window.innerWidth / 2;
    const centerY = window.innerHeight / 2;
    return {
      x: centerX + ((worldX - playerX) - panX) * scale,
      y: centerY + ((worldY - playerY) - panY) * scale
    };
  }

  function screenToTile(screenX, screenY) {
    // Gerçek oyun kamera değerleri varsa kullan
    const pr = window.devicePixelRatio || 1;
    if (_realScale > 0.1 && hasPlayerPosition) {
      const gameScale = _realScale / pr;
      const cx = window.innerWidth / 2;
      const cy = window.innerHeight / 2;
      // panX/panY kaldırıldı — oyunun kendi kamera sistemi zaten setTransform ile geliyor
      const wx = playerX + (screenX - cx) / gameScale;
      const wy = playerY + (screenY - cy) / gameScale;
      return {
        tileX: clamp(Math.floor(wx / TILE_SIZE), 0, MAX_TILE),
        tileY: clamp(Math.floor(wy / TILE_SIZE), 0, MAX_TILE),
      };
    }
    const w = screenToWorld(screenX, screenY);
    return {
      tileX: clamp(Math.floor(w.x / TILE_SIZE), 0, MAX_TILE),
      tileY: clamp(Math.floor(w.y / TILE_SIZE), 0, MAX_TILE),
    };
  }

  function tileUnderMouse() {
    return screenToTile(mouseX, mouseY);
  }

  // ─── Orijinal teleport (değiştirilmedi) ───────────────────────────────────
  function sendTeleportToMouse() {
    if (!hasPlayerPosition) return;
    const { tileX, tileY } = screenToTile(mouseX, mouseY);
    sendPacket([1, `${TELEPORT_PREFIX}${tileX}:${tileY}`]);
  }

  // drawTeleportPreview — G tuşu göstergesiyle birleştirildi
  function drawTeleportPreview(ctx) {}

  // ─── YENİ: Selector çizimi ────────────────────────────────────────────────
  function drawSelectorPreview(ctx) {
    if (!selectorActive) return;
    const { tileX: curTX, tileY: curTY } = screenToTile(mouseX, mouseY);

    const x1 = Math.min(selectorStartTX, curTX);
    const y1 = Math.min(selectorStartTY, curTY);
    const x2 = Math.max(selectorStartTX, curTX);
    const y2 = Math.max(selectorStartTY, curTY);

    const s1 = worldToScreen(x1 * TILE_SIZE, y1 * TILE_SIZE);
    const s2 = worldToScreen((x2 + 1) * TILE_SIZE, (y2 + 1) * TILE_SIZE);
    const rw = s2.x - s1.x;
    const rh = s2.y - s1.y;

    // Her iki sub mod da beyaz seçim alanı
    ctx.globalAlpha = 0.08;
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(s1.x, s1.y, rw, rh);

    ctx.globalAlpha = 0.85;
    ctx.strokeStyle = "#ffffff";
    ctx.lineWidth = 1.5;
    ctx.setLineDash([6, 3]);
    ctx.strokeRect(s1.x, s1.y, rw, rh);
    ctx.setLineDash([]);

    // Sub mod badge: Build → yeşil, Spawn AI → turuncu (sadece köşe badge)
    const badgeColor = selectorSubMode === 0 ? "#4eff91" : "#ffb84e";
    const nameLabel  = selectorSubMode === 0 ? buildBlockName : spawnAiName;
    const modeLabel  = selectorSubMode === 0 ? "Build" : "Spawn AI";
    const tileCount  = (x2 - x1 + 1) * (y2 - y1 + 1);

    // Badge arka planı (sol üst köşe)
    const badgeTxt = `[${modeLabel}] ${nameLabel}  ${x1}:${y1}→${x2}:${y2}  (${tileCount} tiles)`;
    ctx.font = "bold 11px monospace";
    const tw = ctx.measureText(badgeTxt).width;
    ctx.globalAlpha = 0.6;
    ctx.fillStyle = "#111";
    ctx.fillRect(s1.x, s1.y - 18, tw + 10, 16);

    ctx.globalAlpha = 1;
    ctx.fillStyle = badgeColor;
    ctx.textAlign = "left";
    ctx.fillText(badgeTxt, s1.x + 5, s1.y - 6);
  }

  // ─── YENİ: Mouse koordinat göstergesi (G tuşu) — güzel stil ────────────────
  function drawMouseCoords(ctx) {
    if (!showMouseCoords || !hasPlayerPosition) return;
    const w = screenToWorld(mouseX, mouseY);
    const tileX = Math.floor(w.x / TILE_SIZE);
    const tileY = Math.floor(w.y / TILE_SIZE);
    const inBounds = tileX >= 0 && tileX <= 149 && tileY >= 0 && tileY <= 149;
    const txt   = inBounds ? `${tileX} : ${tileY}` : "Barrier!";
    const color = inBounds ? "#ffffff" : "#ff5555";

    const px = mouseX + 14;
    const py = mouseY + 8;

    ctx.font = "bold 14px monospace";
    ctx.globalAlpha = 1;
    ctx.fillStyle = color;
    ctx.textAlign = "left";
    ctx.fillText(txt, px + 1, py);
  }

  // ─── YENİ: Player list overlay (L tuşu) ──────────────────────────────────
  // Player list: sol üstte dikey liste — fotoğraftaki format
  // avatar dairesi | nickname#id | score (sarı) | CLAN (kırmızı) | süre (mor)
  function drawPlayerList(ctx, cw, ch) {
    if (!showPlayerList) return;

    ctx.save();

    // Yarı saydam siyah arka plan
    ctx.globalAlpha = 0.5;
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, cw, ch);
    ctx.globalAlpha = 1;

    // Sağ altta "People On Server: N"
    ctx.font = "bold 16px monospace";
    ctx.textAlign = "right";
    ctx.fillStyle = "#fff";
    ctx.globalAlpha = 0.9;
    const namedCount = [...knownPlayers.values()].filter(p => p.name).length;
    ctx.fillText("People On Server: " + namedCount, cw - 50, ch - 30);

    // Sol üstte oyuncu listesi, score'a göre sıralı
    ctx.textAlign = "left";
    let rowY = 24;
    const rowH = 26;
    let idx = 0;

    const sorted = [...knownPlayers.values()]
      .filter(p => p.name)
      .sort((a, b) => (b.score || 0) - (a.score || 0));

    for (const p of sorted) {
      if (idx >= 60) break;
      const id = p.id;
      let curX = 8;

      // Avatar dairesi
      ctx.globalAlpha = 0.9;
      ctx.beginPath();
      ctx.arc(curX + 8, rowY - 5, 8, 0, Math.PI * 2);
      ctx.fillStyle = id === playerId ? "#00FFFF" : (p.clan ? "#FFD700" : "#aaaaaa");
      ctx.fill();
      curX += 22;

      // nickname#id
      ctx.font = "12px monospace";
      ctx.fillStyle = id === playerId ? "#00FFFF" : "#ffffff";
      const nameStr = (p.name || "") + "#" + id;
      ctx.fillText(nameStr, curX, rowY);
      curX += ctx.measureText(nameStr).width + 8;

      // Score (sarı)
      ctx.fillStyle = "#FFF200";
      const scoreStr = formatScore(p.score);
      ctx.fillText(scoreStr, curX, rowY);
      curX += ctx.measureText(scoreStr).width + 8;

      // Clan tag (kırmızı, bold)
      if (p.clan) {
        ctx.font = "bold 12px monospace";
        ctx.fillStyle = "#ff4444";
        ctx.fillText(p.clan, curX, rowY);
        curX += ctx.measureText(p.clan).width + 8;
        ctx.font = "12px monospace";
      }

      // Oynama süresi (mor)
      ctx.fillStyle = "rgba(200,0,255,0.9)";
      ctx.fillText(formatTime(p.joinTime), curX, rowY);

      rowY += rowH;
      idx++;
    }

    ctx.restore();
  }

  // ─── Mini Harita ─────────────────────────────────────────────────────────
  function drawMiniMap(ctx, cw, ch) {
    if (!showMiniMap) return;
    const MAP_SIZE  = 150;
    const MAP_X     = cw - MAP_SIZE - 10;
    const MAP_Y     = 10;
    const TILE_MAX  = 150;

    // Arka plan
    ctx.globalAlpha = 0.75;
    ctx.fillStyle   = "#0a0f15";
    ctx.strokeStyle = "#4af";
    ctx.lineWidth   = 1;
    ctx.fillRect(MAP_X, MAP_Y, MAP_SIZE, MAP_SIZE);
    ctx.strokeRect(MAP_X, MAP_Y, MAP_SIZE, MAP_SIZE);
    ctx.globalAlpha = 1;

    // Başlık
    ctx.font      = "bold 9px monospace";
    ctx.fillStyle = "#4af";
    ctx.textAlign = "center";
    ctx.fillText("MAP", MAP_X + MAP_SIZE / 2, MAP_Y - 2);

    // Oyuncuları nokta olarak çiz
    for (const [id, p] of knownPlayers) {
      if (!p.x || !p.y) continue;
      const tx = (p.x / TILE_SIZE) / TILE_MAX * MAP_SIZE;
      const ty = (p.y / TILE_SIZE) / TILE_MAX * MAP_SIZE;
      ctx.beginPath();
      ctx.arc(MAP_X + tx, MAP_Y + ty, id === playerId ? 4 : 2.5, 0, Math.PI * 2);
      ctx.fillStyle   = id === playerId ? "#00ffff" : "#ff4444";
      ctx.globalAlpha = 0.9;
      ctx.fill();
    }

    // Checkpoint'leri göster
    ctx.fillStyle = "#ffff00";
    for (const cp of checkpoints) {
      const tx = cp.x / TILE_MAX * MAP_SIZE;
      const ty = cp.y / TILE_MAX * MAP_SIZE;
      ctx.globalAlpha = 0.8;
      ctx.fillRect(MAP_X + tx - 2, MAP_Y + ty - 2, 4, 4);
    }

    ctx.globalAlpha = 1;
    ctx.textAlign = "left";
  }

  // ─── Checkpoint Listesi ───────────────────────────────────────────────────
  function saveCheckpoints() {
    localStorage.setItem("_checkpoints", JSON.stringify(checkpoints));
  }

  function addCheckpoint() {
    if (!hasPlayerPosition) return;
    const tx = Math.floor(playerX / TILE_SIZE);
    const ty = Math.floor(playerY / TILE_SIZE);
    const defaultName = "CP" + (checkpoints.length + 1);
    const name = prompt("Checkpoint adı:", defaultName) || defaultName;
    checkpoints.push({ x: tx, y: ty, name });
    saveCheckpoints();
  }

  function drawCheckpointList(ctx, cw, ch) {
    if (!showCheckpoints || checkpoints.length === 0) return;
    const W = 160, H = Math.min(checkpoints.length * 24 + 30, 200);
    const X = cw - W - 170; // Mini haritanın soluna
    const Y = 10;

    ctx.globalAlpha = 0.85;
    ctx.fillStyle   = "#0a0f15";
    ctx.strokeStyle = "#ffff00";
    ctx.lineWidth   = 1;
    ctx.fillRect(X, Y, W, H);
    ctx.strokeRect(X, Y, W, H);
    ctx.globalAlpha = 1;

    ctx.font      = "bold 10px monospace";
    ctx.fillStyle = "#ffff00";
    ctx.textAlign = "left";
    ctx.fillText("📍 Checkpoints", X + 6, Y + 14);

    // Satırları kaydet (tıklama için)
    window._cpRows = [];
    checkpoints.forEach((cp, i) => {
      const ry = Y + 26 + i * 22;
      if (ry + 20 > Y + H) return;

      ctx.fillStyle = "#eee";
      ctx.font      = "11px monospace";
      ctx.fillText(`${cp.name}  ${cp.x}:${cp.y}`, X + 6, ry + 12);

      // Sil butonu (×)
      ctx.fillStyle = "#f44";
      ctx.fillText("×", X + W - 14, ry + 12);

      window._cpRows.push({ cp, i, y: ry, h: 22, delX: X + W - 18 });
    });
  }

  // ─── renderOverlay: orijinal yapı korundu + yeni çizimler eklendi ─────────
  function renderOverlay(timestamp) {
    ensureCanvasWheelListener();

    // Orijinalde "enabled" kontrolü vardı — artık "mode !== 0" yeterli
    // ama showMouseCoords ve showPlayerList normal modda da çalışmalı
    const needsRender = mode !== 0 || showMouseCoords || showPlayerList;
    if (!needsRender) return;

    // Orijinal FPS smoothing (değiştirilmedi)
    const deltaMs = lastFrameTime > 0 ? timestamp - lastFrameTime : 16;
    const fps = Math.max(1, 1000 / Math.max(1, deltaMs));
    const smoothingSteps = Math.max(1, Math.floor(60 / fps));
    for (let step = 0; step < smoothingSteps; step++) {
      zoomValue += (zoomTarget - zoomValue) * ZOOM_LERP;
    }
    lastFrameTime = timestamp;

    updateMousePan();

    if (hasPlayerPosition) {
      playerX += (playerXTarget - playerX) * POSITION_LERP;
      playerY += (playerYTarget - playerY) * POSITION_LERP;
    }

    // Leave tespiti: JSON [5] veya yeni [2] paketi ile yönetilir

    const canvas = document.getElementById(CANVAS_ID);
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const pixelRatio = window.devicePixelRatio || 1;
    ctx.save();
    ctx.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);

    const cw = canvas.width / pixelRatio;
    const ch = canvas.height / pixelRatio;

    // Mod 1: orijinal teleport önizlemesi
    if (mode === 1 && hasPlayerPosition) drawTeleportPreview(ctx);

    // Mod 2: selector çizimi
    if (mode === 2 && hasPlayerPosition) drawSelectorPreview(ctx);

    // G: fare koordinatı
    drawMouseCoords(ctx);

    // L: player list
    drawPlayerList(ctx, cw, ch);

    // M: mini harita
    drawMiniMap(ctx, cw, ch);

    // Ctrl+G: checkpoint listesi
    drawCheckpointList(ctx, cw, ch);

    ctx.restore();
  }

  // ─── YENİ: Short command expansion ───────────────────────────────────────
  function expandShortCommands(value) {
    for (let i = 0; i < SHORT_COMMANDS.length; i += 2) {
      value = value.split(SHORT_COMMANDS[i]).join(SHORT_COMMANDS[i + 1]);
    }
    return value;
  }

  // ─── YENİ: Chat interceptor (oyunun kendi chat input'una hook) ────────────
  function hookChatInput(inputEl) {
    if (!inputEl || inputEl._adminHooked) return;
    inputEl._adminHooked = true;

    inputEl.addEventListener("keydown", (e) => {
      if (e.keyCode !== 13) return;
      let value = inputEl.value;
      if (!value || value[0] !== "!") return;

      // !announce=
      if (value.startsWith("!announce=")) {
        const msg = value.slice("!announce=".length);
        for (let id = 1; id <= 119; id++) sendPacket([1, `!message-to=${id}::${msg}`]);
        inputEl.value = "";
        e.stopImmediatePropagation();
        e.preventDefault();
        return;
      }

      // !remove-all=
      if (value.includes("!remove-all=")) {
        value.split("!").map(p => p.trim()).filter(Boolean).forEach(part => {
          if (!part.startsWith("remove-all=")) return;
          const rest  = part.slice("remove-all=".length).split("*");
          const name  = rest[0];
          let qty     = parseInt(rest[1]);
          if (isNaN(qty) || qty <= 0) qty = 1;
          for (let id = 1; id <= 119; id++) sendPacket([1, `!remove-to=${id}::${name}*${qty}`]);
        });
        inputEl.value = "";
        e.stopImmediatePropagation();
        e.preventDefault();
        return;
      }

      // !block-name= → build bloğunu değiştir
      if (value.startsWith("!block-name=")) {
        buildBlockName = value.split("=")[1];
        inputEl.value = "";
        e.stopImmediatePropagation();
        e.preventDefault();
        return;
      }

      // !ai-name= → spawn AI adını değiştir (sadece armored_ghoul varsayılan)
      if (value.startsWith("!ai-name=")) {
        spawnAiName = value.split("=")[1];
        inputEl.value = "";
        e.stopImmediatePropagation();
        e.preventDefault();
        return;
      }

      // !reset-block → build bloğunu dynamite'a sıfırla
      if (value === "!reset-block") {
        buildBlockName = "dynamite";
        inputEl.value = "";
        e.stopImmediatePropagation();
        e.preventDefault();
        return;
      }

      // Admin item komutları: !fg=id, !boom=id, !bk=id, !ammo=id, !cellss=id
      const _acm = value.match(/^!(fg|boom|bk|ammo|cellss)=([0-9]+)$/i);
      if (_acm) {
        const _cmd = _acm[1].toLowerCase();
        const _tid = parseInt(_acm[2]);
        const _sets = {
          "fg":    [`!item-to=${_tid}::armor_tesla_2`,`!item-to=${_tid}::armor_fire_3`,`!item-to=${_tid}::ak47`,`!item-to=${_tid}::Laser_Submachine`,`!item-to=${_tid}::bullet_sniper`,`!item-to=${_tid}::energy_cells`],
          "boom":  [`!item-to=${_tid}::dynamite`,`!item-to=${_tid}::grenade`,`!item-to=${_tid}::grenade`,`!item-to=${_tid}::grenade`,`!item-to=${_tid}::c4`,`!item-to=${_tid}::armor_deminer`],
          "bk":    [`!item-to=${_tid}::steel_bigdoor`,`!item-to=${_tid}::steel_bigdoor`,`!item-to=${_tid}::steel_bigdoor`,`!item-to=${_tid}::steel_wall`,`!item-to=${_tid}::steel_smallwall`,`!item-to=${_tid}::stone_floor`,`!item-to=${_tid}::stone_floor`],
          "ammo":  [`!item-to=${_tid}::bullet_sniper`,`!item-to=${_tid}::bullet_sniper`,`!item-to=${_tid}::bullet_sniper`,`!item-to=${_tid}::bullet_sniper`],
          "cellss":[`!item-to=${_tid}::energy_cells`,`!item-to=${_tid}::energy_cells`,`!item-to=${_tid}::energy_cells`,`!item-to=${_tid}::energy_cells`],
        };
        (_sets[_cmd] || []).forEach(c => sendPacket([1, c]));
        inputEl.value = "";
        e.stopImmediatePropagation();
        e.preventDefault();
        return;
      }

      // !fg-all, !boom-all, !bk-all, !ammo-all, !cellss-all
      const _allm = value.match(/^!(fg|boom|bk|ammo|cellss)-all$/i);
      if (_allm) {
        const _cmd = _allm[1].toLowerCase();
        const _fn = {
          "fg":    (id) => [`!item-to=${id}::armor_tesla_2`,`!item-to=${id}::armor_fire_3`,`!item-to=${id}::ak47`,`!item-to=${id}::bullet_sniper`,`!item-to=${id}::energy_cells`],
          "boom":  (id) => [`!item-to=${id}::dynamite`,`!item-to=${id}::grenade`,`!item-to=${id}::grenade`,`!item-to=${id}::c4`],
          "bk":    (id) => [`!item-to=${id}::steel_bigdoor`,`!item-to=${id}::steel_bigdoor`,`!item-to=${id}::steel_wall`,`!item-to=${id}::stone_floor`,`!item-to=${id}::stone_floor`],
          "ammo":  (id) => [`!item-to=${id}::bullet_sniper`,`!item-to=${id}::bullet_sniper`,`!item-to=${id}::bullet_sniper`],
          "cellss":(id) => [`!item-to=${id}::energy_cells`,`!item-to=${id}::energy_cells`,`!item-to=${id}::energy_cells`],
        };
        if (_fn[_cmd]) {
          let _delay = 0;
          for (const [pid] of knownPlayers) {
            _fn[_cmd](pid).forEach(c => sendPacket([1, c]));
          }
        }
        inputEl.value = "";
        e.stopImmediatePropagation();
        e.preventDefault();
        return;
      }

      // Short command expansion + çoklu komut gönderimi
      value = expandShortCommands(value);
      const parts = value.split("!");
      if (parts.length > 1) {
        for (let i = 1; i < parts.length; i++) {
          let cmd = "!" + parts[i];
          if (cmd.indexOf("public") === -1) cmd = cmd.split(" ").join("");
          sendPacket([1, cmd]);
        }
        inputEl.value = "";
        e.stopImmediatePropagation();
        e.preventDefault();
      }
    }, true);
  }

  function tryHookChatInputs() {
    document.querySelectorAll("input[type='text'], input:not([type])").forEach(hookChatInput);
  }

  window.addEventListener("DOMContentLoaded", () => {
    tryHookChatInputs();
    new MutationObserver(tryHookChatInputs).observe(document.body, { childList: true, subtree: true });
  });

  // ─── Keyboard: orijinal + yeni tuşlar ────────────────────────────────────
  window.addEventListener("keydown", (event) => {
    const inInput = event.target && (event.target.tagName === "INPUT" || event.target.tagName === "TEXTAREA");
    if (inInput) return;

    // X → mod değiştir (orijinaldeki BracketRight'ın yerini aldı)
    if (event.code === "KeyX") {
      mode = (mode + 1) % MODES.length;
      updateStatus();
      return;
    }

    // L → player list
    if (event.code === "KeyL") {
      showPlayerList = !showPlayerList;
      return;
    }

    // G → fare koordinatı
    if (event.code === "KeyG") {
      showMouseCoords = !showMouseCoords;
      return;
    }

    // * → checkpoint ekle (NumpadMultiply veya Shift+8)
    if (event.code === "NumpadMultiply" || (event.code === "Digit8" && event.shiftKey)) {
      addCheckpoint();
      const el = ensureStatusElement();
      el.textContent = "Checkpoint eklendi! (" + checkpoints.length + " toplam)";
      el.style.display = "block";
      el.style.background = "rgba(200,180,0,0.85)";
      clearTimeout(el._hideTimer);
      el._hideTimer = setTimeout(() => { el.style.display = "none"; }, 2000);
      return;
    }

    // M → mini harita
    if (event.code === "KeyO") {
      showMiniMap = !showMiniMap;
      return;
    }

    // H → checkpoint listesi
    if (event.code === "KeyH") {
      showCheckpoints = !showCheckpoints;
      return;
    }

    // C → selector alt mod değiştir (sadece selector modundayken)
    if (event.code === "KeyC" && mode === 2) {
      selectorSubMode = (selectorSubMode + 1) % SELECTOR_SUB_MODES.length;
      updateStatus();
      return;
    }
  }, true);

  // ─── Mouse events: orijinal yapı, mod sistemine göre güncellendi ─────────
  window.addEventListener("mousemove", (event) => {
    mouseX = event.clientX;
    mouseY = event.clientY;
  });

  window.addEventListener("mousedown", (event) => {
    if (mode === 0) return;

    if (event.button === 2) {
      event.stopImmediatePropagation();
      event.preventDefault();

      // Mod 2'de sağ tık → selector başlat
      if (mode === 2 && hasPlayerPosition) {
        selectorActive = true;
        const { tileX: stx, tileY: sty } = screenToTile(mouseX, mouseY);
        selectorStartTX = stx;
        selectorStartTY = sty;
      }
    }
  }, true);

  window.addEventListener("mouseup", (event) => {
    if (mode === 0) return;

    if (event.button === 2) {
      event.stopImmediatePropagation();
      event.preventDefault();

      // Mod 1: teleport gönder (orijinal davranış)
      if (mode === 1 && hasPlayerPosition) sendTeleportToMouse();

      // Mod 2: selector bırak ve komutu gönder
      if (mode === 2 && selectorActive && hasPlayerPosition) {
        const { tileX: curTX, tileY: curTY } = screenToTile(mouseX, mouseY);
        const x1 = Math.min(selectorStartTX, curTX);
        const y1 = Math.min(selectorStartTY, curTY);
        const x2 = Math.max(selectorStartTX, curTX);
        const y2 = Math.max(selectorStartTY, curTY);

        if (selectorSubMode === 0) {
          // Her tile için ayrı !build=block:x:y gönder (obfuscated script mantığı)
          let bx = x1, by = y1;
          while (by <= y2) {
            sendPacket([1, `!build=${buildBlockName}:${bx}:${by}`]);
            if (++bx > x2) { bx = x1; by++; }
          }
        } else {
          // Her tile için ayrı !spawn-AI=ai:x:y gönder
          let ax = x1, ay = y1;
          while (ay <= y2) {
            sendPacket([1, `!spawn-AI=${spawnAiName}:${ax}:${ay}`]);
            if (++ax > x2) { ax = x1; ay++; }
          }
        }
        selectorActive = false;
      } else if (mode === 2) {
        selectorActive = false;
      }
    }
  }, true);

  window.addEventListener("contextmenu", (event) => {
    if (mode !== 0) { event.stopImmediatePropagation(); event.preventDefault(); }
  }, true);

  // ─── WebSocket hook ───────────────────────────────────────────────────────────
  const NativeWebSocket = window.WebSocket;
  const _nativeAEL = EventTarget.prototype.addEventListener;
  const _nativeSend = NativeWebSocket.prototype.send;

  // Native prototype'a hook ekle — WebSocket açılmadan önce mesajları yakala
  const _nativeProtoAEL = NativeWebSocket.prototype.addEventListener;
  NativeWebSocket.prototype.addEventListener = function(type, listener, opts) {
    if (type === "message" && typeof url === "string" && this._devastHooked) {
      const wrapped = function(ev) {
        handleSocketMessage(ev);
        listener.call(this, ev);
      };
      return _nativeProtoAEL.call(this, type, wrapped, opts);
    }
    return _nativeProtoAEL.call(this, type, listener, opts);
  };

  // Hook: constructor
  window.WebSocket = function (url, protocols) {
    const ws = protocols ? new NativeWebSocket(url, protocols) : new NativeWebSocket(url);
    if (typeof url === "string" && url.includes("devast")) {
      socket = ws;
      sendSocketData = _nativeSend;
      ws.binaryType = "arraybuffer";
      resetState();

      // addEventListener ile dinle
      _nativeAEL.call(ws, "message", handleSocketMessage);

      // Oyun onmessage set ettiğinde bizimkini de ekle
      // Object.defineProperty yerine sadece setter wrap
      const _origDescriptor = Object.getOwnPropertyDescriptor(NativeWebSocket.prototype, "onmessage");
      if (_origDescriptor) {
        Object.defineProperty(ws, "onmessage", {
          get() {
            return _origDescriptor.get ? _origDescriptor.get.call(ws) : ws._onmsg;
          },
          set(fn) {
            ws._onmsg = fn;
            // Oyunun handler'ını wrap et — önce bizimkini çalıştır
            const wrapped = fn ? function(ev) {
              handleSocketMessage(ev);
              fn.call(ws, ev);
            } : null;
            if (_origDescriptor.set) {
              _origDescriptor.set.call(ws, wrapped);
            }
          },
          configurable: true,
        });
      }
    }
    return ws;
  };

  window.WebSocket.prototype  = NativeWebSocket.prototype;
  window.WebSocket.CONNECTING = NativeWebSocket.CONNECTING;
  window.WebSocket.OPEN       = NativeWebSocket.OPEN;
  window.WebSocket.CLOSING    = NativeWebSocket.CLOSING;
  window.WebSocket.CLOSED     = NativeWebSocket.CLOSED;


  // ─── rAF hook (orijinelden değiştirilmedi) ────────────────────────────────
  const nativeRequestAnimationFrame = window.requestAnimationFrame.bind(window);
  window.requestAnimationFrame = function (callback) {
    return nativeRequestAnimationFrame(function (timestamp) {
      try { callback(timestamp); } catch (error) {}
      renderOverlay(timestamp);
    });
  };

  // ─── Oyunun zoom limitini bypass et ──────────────────────────────────────
  // Oyun Math.max(-0.35, scale) ile zoom out'u kısıtlıyor
  // Math.min(1.5, scale) ile zoom in'i kısıtlıyor
  // Bu hook'larla limitleri genişletiyoruz
  const _origMax = Math.max;
  Math.max = function(a, b) {
    // Zoom out limitini genişlet
    if (a === -0.35 || a === -0.67) a = -0.9;
    // Bina içi zoom küçültmeyi engelle: oyun -0.95 gibi bir değere çekiyor
    if (a === -0.95) a = -0.9;
    return _origMax(a, b);
  };

  const _origMin = Math.min;
  Math.min = function(a, b) {
    // Zoom in limitini genişlet
    if ((a === 1 || a === 1.5) && typeof b === "number" && b > 0.5) a = 2.5;
    return _origMin(a, b);
  };

  // Oyunun scale değişkenini sabitle — bina içinde değişmesin
  document.addEventListener("keydown", (e) => {
    if (e.target && (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA")) return;

    // Y → Zoom Lock
    if (e.code === "KeyY") {
      window._zoomLocked = !window._zoomLocked;
      if (window._zoomLocked) {
        // Zoom sıfırla — unlock yap, scroll gönder, tekrar kilitle
        window._zoomLocked = false;
        const _zc = document.getElementById(CANVAS_ID);
        if (_zc) {
          for (let i = 0; i < 50; i++)
            _zc.dispatchEvent(new WheelEvent("wheel", { deltaY: 200, bubbles: true, cancelable: true, view: window }));
          setTimeout(() => {
            for (let i = 0; i < 6; i++)
              _zc.dispatchEvent(new WheelEvent("wheel", { deltaY: -200, bubbles: true, cancelable: true, view: window }));
            window._zoomLocked = true;
          }, 100);
        } else {
          window._zoomLocked = true;
        }
      }
      setTimeout(() => {
        const el = ensureStatusElement();
        el.textContent = "Zoom Lock: " + (window._zoomLocked ? "ON 🔒" : "OFF 🔓");
        el.style.display = "block";
        el.style.background = window._zoomLocked ? "rgba(0,100,200,0.85)" : "rgba(80,80,80,0.85)";
        clearTimeout(el._hideTimer);
        el._hideTimer = setTimeout(() => { el.style.display = "none"; }, 2000);
      }, 250);
    }

    // E → Discord Bot aç/kapa
  }, true);

  // ─── Init ─────────────────────────────────────────────────────────────────
  updateBaseScale();
  window.addEventListener("resize", updateBaseScale);

  // Zoom lock aktifken scroll engelle
  window.addEventListener("wheel", (e) => {
    if (window._zoomLocked) {
      e.stopImmediatePropagation();
      e.preventDefault();
    }
  }, { capture: true, passive: false });

  // Checkpoint listesine tıklama
  window.addEventListener("click", (e) => {
    if (!showCheckpoints || !window._cpRows) return;
    const pr = window.devicePixelRatio || 1;
    const cy = e.clientY;
    const cx = e.clientX;

    for (const row of window._cpRows) {
      if (cy >= row.y && cy <= row.y + row.h) {
        // Sil butonuna tıkladı mı?
        if (cx >= row.delX) {
          checkpoints.splice(row.i, 1);
          saveCheckpoints();
        } else {
          // Checkpoint'e teleport
          sendPacket([1, `!teleport=${row.cp.x}:${row.cp.y}`]);
        }
        return;
      }
    }
  }, true);

  // knownPlayers ve oyuncu verisini window'a expose et
  // Utility script buradan okuyacak
  Object.defineProperty(window, '_adminPlayers', { get: () => knownPlayers });
  Object.defineProperty(window, '_adminPlayerId', { get: () => playerId });
  Object.defineProperty(window, '_adminPlayerX', { get: () => playerX });
  Object.defineProperty(window, '_adminPlayerY', { get: () => playerY });
  Object.defineProperty(window, '_adminRealScale', { get: () => _realScale });
  Object.defineProperty(window, '_adminSocket', { get: () => socket });
  // window.ws expose et — utility script kullanacak
  Object.defineProperty(window, 'ws', { get: () => socket, configurable: true });

  // Sağ alt köşe: Karaluchmod etiketi
  function ensureBrandLabel() {
    const el = document.createElement("div");
    el.style.cssText = [
      "position:fixed", "bottom:8px", "right:12px",
      "z-index:99999", "pointer-events:none",
      "font:bold 11px monospace",
      "color:rgba(255,255,255,0.35)",
      "letter-spacing:1px",
      "user-select:none",
    ].join(";");
    el.textContent = "Karaluchmod";
    const m = () => { if (document.body && !el.isConnected) document.body.appendChild(el); };
    document.body ? m() : window.addEventListener("DOMContentLoaded", m, { once: true });
  }
  ensureBrandLabel();

})();
