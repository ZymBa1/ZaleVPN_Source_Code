/**
 * ZaleVPN — сервер координации.
 * Cloudflare Worker + KV. Привязка KV: NETS.
 *
 * API:
 *   POST /create        {pubkey, endpoint, lan} -> {code, ip, peers}
 *   POST /join/CODE     {pubkey, endpoint, lan} -> {ip, peers}
 *   GET  /peers/CODE                            -> {peers}
 *
 * Код живёт 24 часа. Не больше 20 участников. Адреса 10.50.0.x:
 * создатель получает .1, остальные — первый свободный .2….
 */

const TTL_MS = 24 * 60 * 60 * 1000;
const MAX_PEERS = 20;
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const PREFIX = "net:";

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,OPTIONS",
  "access-control-allow-headers": "content-type",
};

function reply(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...CORS,
    },
  });
}

function fail(text, status) {
  return reply({ error: text }, status);
}

/** Проверка "ipv4:port". Возвращает нормализованную строку или null. */
function validAddr(value) {
  if (typeof value !== "string") return null;
  const s = value.trim();
  if (s.length < 7 || s.length > 64) return null;
  if (!/^(\d{1,3}\.){3}\d{1,3}:\d{1,5}$/.test(s)) return null;
  const parts = s.split(":");
  const ip = parts[0];
  const port = Number(parts[1]);
  if (!Number.isFinite(port) || port < 1 || port > 65535) return null;
  if (ip.split(".").some((n) => Number(n) > 255)) return null;
  return s;
}

function validKey(value) {
  if (typeof value !== "string") return null;
  const s = value.trim();
  if (s.length < 20 || s.length > 128) return null;
  if (!/^[A-Za-z0-9+/=]+$/.test(s)) return null;
  return s;
}

function makeCode() {
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  let s = "";
  for (const b of bytes) s += ALPHABET[b % ALPHABET.length];
  return s;
}

function publicPeers(net) {
  return net.peers.map((p) => ({
    pubkey: p.pubkey,
    endpoint: p.endpoint,
    lan: p.lan || "",
    ip: p.ip,
  }));
}

async function readBody(request) {
  try {
    const body = await request.json();
    if (!body || typeof body !== "object") return null;
    return body;
  } catch (e) {
    return null;
  }
}

async function readNet(env, code) {
  const key = PREFIX + code;
  const raw = await env.NETS.get(key);
  if (!raw) return null;
  let net;
  try {
    net = JSON.parse(raw);
  } catch (e) {
    await env.NETS.delete(key);
    return null;
  }
  if (
    !net ||
    !Array.isArray(net.peers) ||
    typeof net.exp !== "number" ||
    net.exp <= Date.now()
  ) {
    await env.NETS.delete(key);
    return null;
  }
  return net;
}

async function saveNet(env, net) {
  const seconds = Math.max(60, Math.floor((net.exp - Date.now()) / 1000));
  await env.NETS.put(PREFIX + net.code, JSON.stringify(net), {
    expirationTtl: seconds,
  });
}

function freeIp(net) {
  const used = new Set(net.peers.map((p) => p.ip));
  for (let i = 2; i <= 254; i++) {
    const ip = `10.50.0.${i}`;
    if (!used.has(ip)) return ip;
  }
  return null;
}

async function create(request, env) {
  const body = await readBody(request);
  if (!body) return fail("bad request", 400);

  const pubkey = validKey(body.pubkey);
  const endpoint = validAddr(body.endpoint);
  if (!pubkey || !endpoint) return fail("bad request", 400);
  const lan = validAddr(body.lan) || "";

  let code = null;
  for (let i = 0; i < 10; i++) {
    const candidate = makeCode();
    const existing = await env.NETS.get(PREFIX + candidate);
    if (!existing) {
      code = candidate;
      break;
    }
  }
  if (!code) return fail("busy", 503);

  const now = Date.now();
  const net = {
    code,
    created: now,
    exp: now + TTL_MS,
    peers: [{ pubkey, endpoint, lan, ip: "10.50.0.1", joined: now }],
  };
  await saveNet(env, net);
  return reply({ code, ip: "10.50.0.1", peers: publicPeers(net) });
}

async function join(rawCode, request, env) {
  const code = String(rawCode || "").toUpperCase();
  if (!/^[A-Z0-9]{4,12}$/.test(code)) return fail("not found", 404);

  const body = await readBody(request);
  if (!body) return fail("bad request", 400);

  const pubkey = validKey(body.pubkey);
  const endpoint = validAddr(body.endpoint);
  if (!pubkey || !endpoint) return fail("bad request", 400);
  const lan = validAddr(body.lan) || "";

  const net = await readNet(env, code);
  if (!net) return fail("not found", 404);

  // Повторный вход с тем же ключом (перезапуск приложения) — обновляем адрес,
  // а не занимаем новый IP.
  const existing = net.peers.find((p) => p.pubkey === pubkey);
  if (existing) {
    existing.endpoint = endpoint;
    existing.lan = lan;
    await saveNet(env, net);
    return reply({ ip: existing.ip, peers: publicPeers(net) });
  }

  if (net.peers.length >= MAX_PEERS) return fail("full", 409);
  const ip = freeIp(net);
  if (!ip) return fail("full", 409);

  net.peers.push({ pubkey, endpoint, lan, ip, joined: Date.now() });
  await saveNet(env, net);
  return reply({ ip, peers: publicPeers(net) });
}

async function peers(rawCode, env) {
  const code = String(rawCode || "").toUpperCase();
  if (!/^[A-Z0-9]{4,12}$/.test(code)) return fail("not found", 404);
  const net = await readNet(env, code);
  if (!net) return fail("not found", 404);
  return reply({ peers: publicPeers(net) });
}

export default {
  async fetch(request, env) {
    try {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: CORS });
      }

      const url = new URL(request.url);
      const parts = url.pathname.split("/").filter((p) => p.length > 0);

      if (parts.length === 0) {
        return reply({ ok: true, service: "ZaleVPN" });
      }
      if (parts[0] === "create" && parts.length === 1 && request.method === "POST") {
        return await create(request, env);
      }
      if (parts[0] === "join" && parts.length === 2 && request.method === "POST") {
        return await join(parts[1], request, env);
      }
      if (parts[0] === "peers" && parts.length === 2 && request.method === "GET") {
        return await peers(parts[1], env);
      }
      return fail("not found", 404);
    } catch (e) {
      return fail("server error", 500);
    }
  },
};
