const ADMIN_USER = "Agung220903";
const ADMIN_PASS = "@Wanda025";
const ADMIN_SECRET = "GN_SECRET_9824_K9X_VIP";

const PLANS = {
  "1_week": { id: "1_week", name: "Weekly VIP Pass", durationDays: 7, durationMs: 7 * 86400000, price: 20000, badge: "Flexible Pass" },
  "1_month": { id: "1_month", name: "Monthly VIP Pass", durationDays: 30, durationMs: 30 * 86400000, price: 50000, badge: "Most Popular" },
  "6_months": { id: "6_months", name: "Semi-Annual VIP Pass", durationDays: 180, durationMs: 180 * 86400000, price: 180000, badge: "Save 40%" },
  "1_year": { id: "1_year", name: "Annual VIP Pass", durationDays: 365, durationMs: 365 * 86400000, price: 290000, badge: "Best Value" }
};

const CREDIT_PACKS = {
  "credits_5": { id: "credits_5", name: "5 VIP Credits", credits: 5, price: 3000, badge: "Starter" },
  "credits_10": { id: "credits_10", name: "10 VIP Credits", credits: 10, price: 5000, badge: "Popular" },
  "credits_25": { id: "credits_25", name: "25 VIP Credits", credits: 25, price: 10000, badge: "Gamer Value" },
  "credits_60": { id: "credits_60", name: "60 VIP Credits", credits: 60, price: 20000, badge: "Titan Bonus" }
};

const CREDIT_REDEEM_ACTIONS = {
  "boost_session": { action: "boost_session", name: "VIP Boost Session (90 Mins)", credits: 1, durationMs: 90 * 60000 },
  "session_45m": { action: "session_45m", name: "Ranked Match Pass (45 Mins)", credits: 1, durationMs: 45 * 60000 },
  "day_pass": { action: "day_pass", name: "Full Day VIP Pass (24 Hours)", credits: 5, durationMs: 24 * 3600000 },
  "pass_24h": { action: "pass_24h", name: "Full Day VIP Pass (24 Hours)", credits: 5, durationMs: 24 * 3600000 },
  "grind_3h": { action: "grind_3h", name: "Grind Session Pass (3 Hours)", credits: 3, durationMs: 3 * 3600000 },
  "tournament_12h": { action: "tournament_12h", name: "Tournament Pass (12 Hours)", credits: 6, durationMs: 12 * 3600000 }
};

function normalizeDeviceId(raw) {
  if (!raw) return "";
  return String(raw).trim().toLowerCase();
}

function isValidDeviceId(id) {
  return typeof id === "string" && /^[A-Za-z0-9_-]{8,64}$/.test(id);
}

function isValidOrderId(id) {
  return typeof id === "string" && /^[A-Za-z0-9_-]{6,80}$/.test(id);
}

async function hmacHex(secret, message) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(message));
  return [...new Uint8Array(sig)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}


function calculatePakasirFee(amount, paymentMethod, bank) {
  const method = (paymentMethod || "qris").toLowerCase().trim();
  if (method === "payment_link") return 0;
  if (method === "qris") {

    if (amount <= 105000) {
      return Math.round(amount * 0.007) + 310;
    } else {
      return Math.round(amount * 0.01);
    }
  }
  const b = (bank || "").toLowerCase().trim();
  if (b.includes("artha") || b.includes("sampoerna")) {
    return 2000;
  }
  return 3500;
}

function getPakasirMethod(paymentMethod, bank, amount = 20000) {
  const method = (paymentMethod || "qris").toLowerCase().trim();
  if (method === "payment_link") return "payment_link";
  if (method === "qris" || amount < 10000) return "qris";

  const b = (bank || "").toLowerCase().trim();
  if (b.includes("bni")) return "bni_va";
  if (b.includes("bri")) return "bri_va";
  if (b.includes("cimb")) return "cimb_niaga_va";
  if (b.includes("permata")) return "permata_va";
  if (b.includes("maybank")) return "maybank_va";
  if (b.includes("sampoerna")) return "sampoerna_va";
  if (b.includes("bnc") || b.includes("neo")) return "bnc_va";
  if (b.includes("artha")) return "artha_graha_va";
  
  if (b.includes("bca") || b.includes("mandiri")) return "qris";
  return "bri_va";
}

const DEFAULT_FEATURES = {
  version: 2,
  last_updated: "2026-09-16",
  disabled_components: [],
  disabled_panels: [],
  component_overrides: {
    touch_tuning: { enabled: true, disabled_reason: "" },
    macro_studio: { enabled: true, disabled_reason: "" },
    cyber_jukebox: { enabled: true, disabled_reason: "" },
    screen_record: { enabled: true, disabled_reason: "" },
    system_optimizer: { enabled: true, disabled_reason: "" },
    antivirus: { enabled: true, disabled_reason: "" },
    network_vpn: { enabled: true, disabled_reason: "" },
    task_manager: { enabled: true, disabled_reason: "" },
    gpu_graphics: { enabled: true, disabled_reason: "" },
    fps_lock: { enabled: true, disabled_reason: "" }
  },
  emergency_broadcast: {
    active: false,
    message: ""
  }
};

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "*",
  "Access-Control-Max-Age": "86400"
};

const MEM_DEVICE_CACHE = new Map();
const MEM_DEVICE_TTL_MS = 60000;

const MEM_ORDER_RECORDS = new Map();
const MEM_CONSUME_GUARD = new Map();

let MEM_DEVICES_LIST = null;
let MEM_DEVICES_LIST_TS = 0;

let MEM_ORDERS_LIST = null;
let MEM_ORDERS_LIST_TS = 0;

let MEM_STATS_CACHE = null;
let MEM_STATS_TS = 0;

let MEM_FEATURES_CACHE = null;
let MEM_FEATURES_TS = 0;

function clearMemoryCaches() {
  MEM_DEVICES_LIST = null;
  MEM_DEVICES_LIST_TS = 0;
  MEM_ORDERS_LIST = null;
  MEM_ORDERS_LIST_TS = 0;
  MEM_STATS_CACHE = null;
  MEM_STATS_TS = 0;
}

function getMemDevice(id) {
  const item = MEM_DEVICE_CACHE.get(id);
  if (!item) return null;
  if (Date.now() - item.ts > MEM_DEVICE_TTL_MS) {
    MEM_DEVICE_CACHE.delete(id);
    return null;
  }
  return item.data;
}

function setMemDevice(id, data) {
  MEM_DEVICE_CACHE.set(id, { ts: Date.now(), data });
  if (MEM_DEVICE_CACHE.size > 2000) {
    const firstKey = MEM_DEVICE_CACHE.keys().next().value;
    MEM_DEVICE_CACHE.delete(firstKey);
  }
}

function delMemDevice(id) {
  MEM_DEVICE_CACHE.delete(id);
}

function getMemOrder(id) {
  return MEM_ORDER_RECORDS.get(id) || null;
}

function setMemOrder(id, data) {
  MEM_ORDER_RECORDS.set(id, data);
  if (MEM_ORDER_RECORDS.size > 1000) {
    const firstKey = MEM_ORDER_RECORDS.keys().next().value;
    MEM_ORDER_RECORDS.delete(firstKey);
  }
}


async function safeJson(request) {
  try {
    return await request.json();
  } catch (_) {
    return {};
  }
}

async function safeKvGet(kv, key, type = "text") {
  if (!kv) return null;
  try {
    return await kv.get(key, type);
  } catch (_) {
    return null;
  }
}

async function safeKvPut(kv, key, value, options) {
  if (!kv) return false;
  try {
    if (options) {
      await kv.put(key, value, options);
    } else {
      await kv.put(key, value);
    }
    return true;
  } catch (_) {
    return false;
  }
}

async function safeKvDelete(kv, key) {
  if (!kv) return false;
  try {
    await kv.delete(key);
    return true;
  } catch (_) {
    return false;
  }
}

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    let path = url.pathname.replace(/\/+/g, "/");
    if (path.length > 1 && path.endsWith("/")) {
      path = path.slice(0, -1);
    }

    const kv = env.GAMENUKE_KV || env.KV;

    async function getIndexedDevices() {
      const now = Date.now();
      if (MEM_DEVICES_LIST && (now - MEM_DEVICES_LIST_TS < 30000)) {
        return MEM_DEVICES_LIST;
      }
      let list = await safeKvGet(kv, "index:devices", "json");
      if (!list) {
        try {
          const rawList = await kv.list({ prefix: "device:", limit: 200 });
          list = [];
          for (const k of rawList.keys) {
            const d = await safeKvGet(kv, k.name, "json");
            if (d) list.push(d);
          }
          await safeKvPut(kv, "index:devices", JSON.stringify(list.slice(0, 200)));
        } catch (_) {
          list = list || [];
        }
      }
      MEM_DEVICES_LIST = Array.isArray(list) ? list.slice(0, 200) : [];
      MEM_DEVICES_LIST_TS = now;
      return MEM_DEVICES_LIST;
    }

    async function saveIndexedDevices(devices) {
      const sliced = Array.isArray(devices) ? devices.slice(0, 200) : [];
      await safeKvPut(kv, "index:devices", JSON.stringify(sliced));
      MEM_DEVICES_LIST = sliced;
      MEM_DEVICES_LIST_TS = Date.now();
      MEM_STATS_CACHE = null;
    }

    async function cancelPakasirTransaction(orderId, amount, txnId = null) {
      const slug = env.PAKASIR_SLUG || env.PAKASIR_PROJECT;
      const apiKey = env.PAKASIR_API_KEY;
      if (!slug || !apiKey) return false;
      try {
        if (txnId) {

          const resp = await fetch(`https://app.pakasir.com/api/v2/cancel-transaction/${encodeURIComponent(slug)}/${encodeURIComponent(txnId)}`, {
            method: "POST",
            headers: {
              "X-Api-Key": apiKey
            }
          });
          return resp.ok;
        } else if (orderId) {
          const resp = await fetch("https://app.pakasir.com/api/transactioncancel", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              project: slug,
              order_id: orderId,
              amount: Number(amount) || 0,
              api_key: apiKey
            })
          });
          return resp.ok;
        }
        return false;
      } catch (_) {
        return false;
      }
    }

    async function getIndexedOrders() {
      const now = Date.now();
      if (MEM_ORDERS_LIST && (now - MEM_ORDERS_LIST_TS < 30000)) {
        return MEM_ORDERS_LIST;
      }
      let list = await safeKvGet(kv, "index:orders", "json");
      if (!list) {
        try {
          const rawList = await kv.list({ prefix: "order:", limit: 200 });
          list = [];
          for (const k of rawList.keys) {
            const o = await safeKvGet(kv, k.name, "json");
            if (o) list.push(o);
          }
          await safeKvPut(kv, "index:orders", JSON.stringify(list.slice(0, 150)));
        } catch (_) {
          list = list || [];
        }
      }
      if (Array.isArray(list)) {
        let changed = false;
        for (const o of list) {
          const isExp = o.status === "pending" && (o.expiresAt ? (now > o.expiresAt) : ((now - (o.createdAt || 0)) > 15 * 60000));
          if (isExp) {
            o.status = "expired";
            changed = true;
            ctx.waitUntil(safeKvPut(kv, `order:${o.orderId}`, JSON.stringify(o), { expirationTtl: 86400 }));
            ctx.waitUntil(cancelPakasirTransaction(o.orderId, o.amount, o.txnId));
          }
        }
        if (changed) {
          ctx.waitUntil(safeKvPut(kv, "index:orders", JSON.stringify(list.slice(0, 150))));
        }
      }
      MEM_ORDERS_LIST = Array.isArray(list) ? list.slice(0, 150) : [];
      MEM_ORDERS_LIST_TS = now;
      return MEM_ORDERS_LIST;
    }

    async function saveIndexedOrders(orders) {
      const sliced = Array.isArray(orders) ? orders.slice(0, 150) : [];
      await safeKvPut(kv, "index:orders", JSON.stringify(sliced));
      MEM_ORDERS_LIST = sliced;
      MEM_ORDERS_LIST_TS = Date.now();
      MEM_STATS_CACHE = null;
    }

    async function applyCompletedOrderToDevice(rawDeviceId, orderRecord) {
      const deviceId = normalizeDeviceId(rawDeviceId);
      if (!deviceId || !orderRecord || !orderRecord.orderId) return null;
      const now = Date.now();
      let dev = getMemDevice(deviceId);
      if (!dev) {
        dev = await safeKvGet(kv, `device:${deviceId}`, "json");
      }

      if (!dev) {
        dev = {
          deviceId,
          planId: isCredit ? "credit_wallet" : (orderRecord.planId || "VIP"),
          planName: isCredit ? "Credit Wallet" : (orderRecord.planName || "VIP Pass"),
          expiresAt: 0,
          creditBalance: 0,
          totalPurchased: 0,
          totalConsumed: 0,
          appliedOrders: [],
          consumeHistory: [],
          createdAt: now,
          updatedAt: now,
          source: "order_completion_init"
        };
      }

      if (!Array.isArray(dev.appliedOrders)) {
        dev.appliedOrders = [];
      }
      if (!Array.isArray(dev.consumeHistory)) {
        dev.consumeHistory = [];
      }

      const orderId = String(orderRecord.orderId).trim();
      const claimKey = `claimed:${orderId}`;
      const claimedBy = await safeKvGet(kv, claimKey);

      if (claimedBy && claimedBy !== deviceId) {
        return dev;
      }

      const isCredit = !!(orderRecord.isCreditPack || CREDIT_PACKS[orderRecord.planId] || (orderRecord.planId && String(orderRecord.planId).startsWith("credits_")));
      const creditToAdd = Number(orderRecord.creditAmount || CREDIT_PACKS[orderRecord.planId]?.credits || (isCredit ? parseInt(String(orderRecord.planId).replace("credits_", ""), 10) : 0) || 0);

      let devChanged = false;
      if (orderId && !dev.appliedOrders.includes(orderId)) {
        dev.appliedOrders.push(orderId);
        if (dev.appliedOrders.length > 100) dev.appliedOrders = dev.appliedOrders.slice(-100);

        if (isCredit && creditToAdd > 0) {
          dev.creditBalance = (Number(dev.creditBalance) || 0) + creditToAdd;
          dev.totalPurchased = (Number(dev.totalPurchased) || 0) + creditToAdd;
          if (!dev.planId || dev.planId === "credit_session") {
            dev.planId = "credit_wallet";
            dev.planName = "Credit Wallet";
          }
          dev.updatedAt = now;
          devChanged = true;
        } else if (!isCredit) {
          const plan = PLANS[orderRecord.planId] || PLANS["1_month"];
          const dur = plan ? plan.durationMs : 30 * 86400000;
          const prevExp = Number(dev.expiresAt || dev.expires_at || 0);
          const curExp = prevExp > now ? prevExp : now;
          dev.expiresAt = curExp + dur;
          dev.planId = plan ? plan.id : orderRecord.planId;
          dev.planName = plan ? plan.name : (orderRecord.planName || "VIP Pass");
          dev.updatedAt = now;
          devChanged = true;
        }
      }

      await safeKvPut(kv, claimKey, deviceId);

      if (devChanged || !getMemDevice(deviceId)) {
        dev.deviceId = deviceId;
        setMemDevice(deviceId, dev);
        await safeKvPut(kv, `device:${deviceId}`, JSON.stringify(dev));

        ctx.waitUntil((async () => {
          try {
            let devs = await getIndexedDevices();
            const idx = devs.findIndex(d => d.deviceId === deviceId);
            if (idx >= 0) devs[idx] = dev; else devs.unshift(dev);
            await saveIndexedDevices(devs);
          } catch (_) {}
        })());
      }

      return dev;
    }

    try {
      if (path === "/health") {
        return jsonResponse({
          status: "ok",
          timestamp: Date.now(),
          service: "Game Nuke Edge Engine v3.6.0-Apeiron",
          region: request.cf?.colo || "EDGE"
        }, 200, 60);
      }

      if (path === "/api/plans") {
        return jsonResponse({
          success: true,
          plans: Object.values(PLANS),
          creditPacks: Object.values(CREDIT_PACKS),
          creditRedeemActions: Object.values(CREDIT_REDEEM_ACTIONS)
        }, 200, 300);
      }

      if (path === "/booster_features.json" || path === "/api/features") {
        const now = Date.now();
        if (MEM_FEATURES_CACHE && (now - MEM_FEATURES_TS < 60000)) {
          return jsonResponse(MEM_FEATURES_CACHE, 200, 120);
        }
        const cached = await safeKvGet(kv, "config:booster_features", "json");
        const features = cached || DEFAULT_FEATURES;
        MEM_FEATURES_CACHE = features;
        MEM_FEATURES_TS = now;
        return jsonResponse(features, 200, 120);
      }

      if (path === "/api/subscription/status") {
        const deviceId = normalizeDeviceId(url.searchParams.get("deviceId") || url.searchParams.get("device_id") || "");
        if (!isValidDeviceId(deviceId)) {
          return jsonResponse({ error: "valid deviceId required" }, 400);
        }

        const now = Date.now();
        let data = getMemDevice(deviceId);
        if (!data) {
          data = await safeKvGet(kv, `device:${deviceId}`, "json");
          if (data) setMemDevice(deviceId, data);
        }

        let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
        let totalCompletedCredits = 0;
        let totalConsumedFromOrders = 0;
        let latestOrderExp = 0;
        let latestPlanId = null;
        let latestPlanName = null;

        for (const o of devOrders) {
          if (o.status === "completed" || o.status === "paid") {
            const isCrd = !!(o.isCreditPack || (o.planId && String(o.planId).startsWith("credits_")) || CREDIT_PACKS[o.planId]);
            if (isCrd) {
              const packCredits = Number(o.creditAmount || CREDIT_PACKS[o.planId]?.credits || (parseInt(String(o.planId).replace("credits_", ""), 10) || 0) || 0);
              totalCompletedCredits += packCredits;
            } else {
              const exp = Number(o.expiresAt || 0);
              if (exp > latestOrderExp) {
                latestOrderExp = exp;
                latestPlanId = o.planId;
                latestPlanName = o.planName;
              }
            }
          } else if (o.status === "consumed" && o.creditsDeducted) {
            totalConsumedFromOrders += Number(o.creditsDeducted) || 0;
          }
        }

        if (!data) {
          if (totalCompletedCredits > 0 || latestOrderExp > now) {
            const calculatedBalance = Math.max(0, totalCompletedCredits - totalConsumedFromOrders);
            data = {
              deviceId,
              planId: latestPlanId || (calculatedBalance > 0 ? "credit_wallet" : null),
              planName: latestPlanName || (latestOrderExp > now ? "VIP Pass" : (calculatedBalance > 0 ? "Credit Wallet" : null)),
              expiresAt: latestOrderExp > 0 ? latestOrderExp : 0,
              creditBalance: calculatedBalance,
              totalPurchased: totalCompletedCredits,
              totalConsumed: totalConsumedFromOrders,
              appliedOrders: devOrders.filter(o => o.status === "completed" || o.status === "paid").map(o => o.orderId),
              consumeHistory: [],
              createdAt: now,
              updatedAt: now,
              source: "device_self_healed"
            };
            setMemDevice(deviceId, data);
            await safeKvPut(kv, `device:${deviceId}`, JSON.stringify(data));
          } else {
            // Free user: NO TRIAL, ZERO KV WRITES (Saves Cloudflare Token & Quota)
            return jsonResponse({
              isActive: false,
              planId: null,
              planName: null,
              expiresAt: 0,
              remainingDays: 0,
              remainingSeconds: 0,
              creditBalance: 0,
              isTrial: false
            }, 200, 0);
          }
        } else {
          if (!Array.isArray(data.appliedOrders)) data.appliedOrders = [];
          let newlyAdded = 0;
          for (const o of devOrders) {
            if ((o.status === "completed" || o.status === "paid") && o.orderId && !data.appliedOrders.includes(o.orderId)) {
              const isCrd = !!(o.isCreditPack || (o.planId && String(o.planId).startsWith("credits_")) || CREDIT_PACKS[o.planId]);
              if (isCrd) {
                const packCredits = Number(o.creditAmount || CREDIT_PACKS[o.planId]?.credits || (parseInt(String(o.planId).replace("credits_", ""), 10) || 0) || 0);
                newlyAdded += packCredits;
                data.appliedOrders.push(o.orderId);
              }
            }
          }
          if (newlyAdded > 0) {
            data.creditBalance = (Number(data.creditBalance) || 0) + newlyAdded;
            data.totalPurchased = (Number(data.totalPurchased) || 0) + newlyAdded;
            data.updatedAt = now;
            setMemDevice(deviceId, data);
            await safeKvPut(kv, `device:${deviceId}`, JSON.stringify(data));
          }
        }

        const queryOrderId = (url.searchParams.get("orderId") || url.searchParams.get("order_id") || "").trim();
        if (queryOrderId && isValidOrderId(queryOrderId) && (!data.appliedOrders || !data.appliedOrders.includes(queryOrderId))) {
          let ord = getMemOrder(queryOrderId);
          if (!ord) {
            const ordStr = await safeKvGet(kv, `order:${queryOrderId}`);
            if (ordStr) { try { ord = JSON.parse(ordStr); } catch (_) {} }
          }
          if (ord && (ord.status === "completed" || ord.status === "paid")) {
            ord.deviceId = deviceId;
            data = await applyCompletedOrderToDevice(deviceId, ord);
          }
        }

        const expAt = data ? Number(data.expiresAt || 0) : 0;
        const currentCredits = data ? Math.max(0, Number(data.creditBalance || 0)) : 0;
        const diff = expAt - now;
        const isTimeActive = diff > 0;
        const remainingDays = isTimeActive ? Math.ceil(diff / 86400000) : 0;
        const remainingSeconds = isTimeActive ? Math.floor(diff / 1000) : 0;

        const effectivePlanId = data ? (data.planId || (isTimeActive ? "VIP" : (currentCredits > 0 ? "credit_wallet" : null))) : null;
        let effectivePlanName = data ? data.planName : null;
        if (!effectivePlanName && isTimeActive) {
          effectivePlanName = "VIP Pass";
        }

        return jsonResponse({
          isActive: isTimeActive,
          planId: effectivePlanId,
          planName: effectivePlanName,
          expiresAt: expAt,
          remainingDays,
          remainingSeconds,
          creditBalance: currentCredits,
          isTrial: false
        }, 200, 0);
      }

      if ((path === "/api/order/create" || path === "/api/orders/create") && request.method === "POST") {
        const body = await safeJson(request);
        const deviceId = (body.deviceId || "").trim();
        const planId = (body.planId || body.packId || "1_month").trim();
        const isCreditPack = !!CREDIT_PACKS[planId];
        const targetItem = isCreditPack ? CREDIT_PACKS[planId] : (PLANS[planId] || PLANS["1_month"]);
        const targetPrice = targetItem.price;

        let paymentMethod = (body.paymentMethod || "qris").toLowerCase().trim();
        if (targetPrice < 10000 && paymentMethod !== "payment_link") {
          paymentMethod = "qris";
        }
        const bank = (body.bank || "bca").toLowerCase().trim();

        if (!deviceId) return jsonResponse({ success: false, errorMessage: "deviceId required" }, 400);

        try {
          const existingDevOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
          const nowCheck = Date.now();
          const activePending = existingDevOrders.find(o => o.status === "pending" && (nowCheck - (o.createdAt || 0)) < 15 * 60000);
          
          if (activePending) {
            const samePlan = activePending.planId === planId;
            const sameMethod = activePending.paymentMethod === paymentMethod;
            const sameBank = paymentMethod === "qris" || (activePending.vaBank && activePending.vaBank.toLowerCase() === bank.toLowerCase());
            
            if (samePlan && sameMethod && sameBank) {
              return jsonResponse({
                success: true,
                orderId: activePending.orderId,
                amount: activePending.amount,
                fee: activePending.fee,
                totalPayment: activePending.totalPayment,
                paymentMethod: activePending.paymentMethod,
                qrisString: activePending.qrisString,
                vaNumber: activePending.vaNumber,
                vaBank: activePending.vaBank,
                isCreditPack: activePending.isCreditPack || false,
                creditAmount: activePending.creditAmount || 0,
                plan: { id: targetItem.id, name: targetItem.name, price: targetItem.price },
                expiredAt: new Date(activePending.expiresAt).toISOString(),
                isReused: true
              });
            } else {
              activePending.status = "cancelled";
              activePending.cancelledAt = nowCheck;
              ctx.waitUntil(cancelPakasirTransaction(activePending.orderId, activePending.amount, activePending.txnId));
              ctx.waitUntil(safeKvPut(kv, `order:${activePending.orderId}`, JSON.stringify(activePending), { expirationTtl: 86400 }));
            }
          }
        } catch (_) {}

        const prefix = isCreditPack ? "CRD" : "GN";
        const orderId = `${prefix}-${targetItem.id.toUpperCase()}-${Date.now().toString().slice(-6)}-${Math.random().toString(36).substring(2, 6).toUpperCase()}`;

        const mode = (await safeKvGet(kv, "config:mode")) || "production";
        const isSandbox = mode === "sandbox";

        const calculatedFee = calculatePakasirFee(targetPrice, paymentMethod, bank);
        const pakasirMethod = getPakasirMethod(paymentMethod, bank, targetPrice);
        let pakasirResult = null;

        const slug = env.PAKASIR_SLUG || env.PAKASIR_PROJECT;
        const apiKey = env.PAKASIR_API_KEY;

        if (slug && apiKey) {
          try {
            // Pakasir API v2: POST https://app.pakasir.com/api/v2/create-transaction/{slug}/{order_id}
            const createUrl = `https://app.pakasir.com/api/v2/create-transaction/${encodeURIComponent(slug)}/${encodeURIComponent(orderId)}`;
            const resp = await fetch(createUrl, {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                "X-Api-Key": apiKey
              },
              body: JSON.stringify({
                method: pakasirMethod,
                amount: Math.round(targetPrice)
              })
            });

            if (resp.ok) {
              pakasirResult = await resp.json();
            } else {
              try {
                const fallbackResp = await fetch(`https://app.pakasir.com/api/transactioncreate/${pakasirMethod}`, {
                  method: "POST",
                  headers: { "Content-Type": "application/json" },
                  body: JSON.stringify({
                    project: slug,
                    order_id: orderId,
                    amount: Math.round(targetPrice),
                    api_key: apiKey
                  })
                });
                if (fallbackResp.ok) {
                  pakasirResult = await fallbackResp.json();
                }
              } catch (_) {}
            }
          } catch (_) {}
        }

        const resData = pakasirResult ? (pakasirResult.payment || pakasirResult.transaction || pakasirResult) : null;
        const txnId = (resData && resData.txn_id) ? String(resData.txn_id) : null;
        const paymentLink = (resData && resData.payment_link) ? String(resData.payment_link) : null;

        const isPaymentLinkMethod = (pakasirMethod === "payment_link");
        const fee = isPaymentLinkMethod ? 0
          : ((resData && resData.fee != null) ? Number(resData.fee) : calculatedFee);
        const totalPayment = isPaymentLinkMethod ? Math.round(targetPrice)
          : ((resData && resData.total_payment != null) ? Number(resData.total_payment) : (targetPrice + fee));
        
        let qrisString = null;
        let vaNumber = null;
        let vaBank = bank ? bank.toUpperCase() : (pakasirMethod.replace("_va", "").toUpperCase());

        if (paymentMethod === "qris" || pakasirMethod === "qris") {
          qrisString = (resData && (resData.qr_string || resData.payment_number || resData.qris_string)) ||
            `00020101021226500014ID.LINKAJA.WWW011893600911002${orderId}520458125303360540${totalPayment}5802ID5910GAME NUKE6007JAKARTA6304`;
        } else if (paymentMethod === "payment_link" || pakasirMethod === "payment_link") {
        } else {
          if (resData && (resData.va_number || resData.payment_number || resData.virtual_account)) {
            vaNumber = String(resData.va_number || resData.payment_number || resData.virtual_account);
            if (resData.bank) vaBank = String(resData.bank).toUpperCase();
          } else {
            const prefix = bank === "bca" ? "12345" : (bank === "mandiri" ? "88908" : "12800");
            vaNumber = `${prefix}${Date.now().toString().slice(-6)}${Math.floor(1000 + Math.random() * 9000)}`;
          }
        }

        const now = Date.now();
        let expiresAt = now + 15 * 60000;
        if (resData && resData.expired_at) {
          const parsed = Date.parse(resData.expired_at);
          if (!isNaN(parsed) && parsed > now) expiresAt = parsed;
        }

        const orderRecord = {
          orderId,
          txnId,
          deviceId,
          planId: targetItem.id,
          isCreditPack,
          creditAmount: isCreditPack ? targetItem.credits : 0,
          amount: targetPrice,
          fee,
          totalPayment,
          paymentMethod,
          pakasirMethod,
          paymentLink,
          vaNumber,
          vaBank,
          status: "pending",
          isSandbox,
          createdAt: now,
          expiresAt
        };

        setMemOrder(orderId, orderRecord);
        await safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 365 * 86400 });
        if (txnId) {
          await safeKvPut(kv, `txn:${txnId}`, orderId, { expirationTtl: 30 * 86400 });
        }

        try {
          let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
          devOrders.unshift({
            orderId,
            txnId,
            planId: targetItem.id,
            planName: targetItem.name,
            isCreditPack,
            creditAmount: isCreditPack ? targetItem.credits : 0,
            amount: targetPrice,
            fee,
            totalPayment,
            paymentMethod,
            qrisString,
            vaNumber,
            vaBank,
            paymentLink,
            status: "pending",
            createdAt: now,
            expiresAt
          });
          if (devOrders.length > 50) devOrders = devOrders.slice(0, 50);
          await safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 });
        } catch (_) {}

        try {
          let allOrders = await getIndexedOrders();
          allOrders.unshift(orderRecord);
          ctx.waitUntil(saveIndexedOrders(allOrders));
        } catch (_) {}

        return jsonResponse({
          success: true,
          orderId,
          txnId,
          amount: targetPrice,
          fee,
          totalPayment,
          paymentMethod,
          qrisString,
          vaNumber,
          vaBank,
          paymentLink,
          feeNote: isPaymentLinkMethod ? "Gateway fee varies by method selected on payment page" : null,
          isCreditPack,
          creditAmount: isCreditPack ? targetItem.credits : 0,
          plan: { id: targetItem.id, name: targetItem.name, price: targetItem.price },
          expiredAt: new Date(expiresAt).toISOString()
        });
      }

      if (path === "/api/payment/fees" || path === "/api/pakasir/fee") {
        const amount = Math.max(1000, Number(url.searchParams.get("amount") || 35000));
        const apiKey = env.PAKASIR_API_KEY;

        let feeData = null;
        if (apiKey) {
          try {
            const feeUrl = `https://app.pakasir.com/api/v2/payment-fee/${amount}`;
            const fResp = await fetch(feeUrl, {
              headers: { "X-Api-Key": apiKey }
            });
            if (fResp.ok) {
              const fJson = await fResp.json();
              if (fJson.status && fJson.data) feeData = fJson.data;
            }
          } catch (_) {}
        }

        if (!feeData) {
          const qrisFee = calculatePakasirFee(amount, "qris");
          const vaArthaFee = 2000;
          const vaStandardFee = 3500;
          feeData = {
            qris: { fee: qrisFee, total: amount + qrisFee },
            bri_va: { fee: vaStandardFee, total: amount + vaStandardFee },
            bni_va: { fee: vaStandardFee, total: amount + vaStandardFee },
            cimb_niaga_va: { fee: vaStandardFee, total: amount + vaStandardFee },
            permata_va: { fee: vaStandardFee, total: amount + vaStandardFee },
            maybank_va: { fee: vaStandardFee, total: amount + vaStandardFee },
            bnc_va: { fee: vaStandardFee, total: amount + vaStandardFee },
            artha_graha_va: { fee: vaArthaFee, total: amount + vaArthaFee },
            sampoerna_va: { fee: vaArthaFee, total: amount + vaArthaFee }
          };
        }

        return jsonResponse({ success: true, amount, fees: feeData }, 200, 300);
      }

      if (path === "/api/orders/device") {
        const deviceId = normalizeDeviceId(url.searchParams.get("deviceId") || url.searchParams.get("device_id") || "");
        if (!deviceId) return jsonResponse({ error: "deviceId required" }, 400);

        let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
        const now = Date.now();
        let changed = false;
        for (const o of devOrders) {
          const isExp = o.status === "pending" && (o.expiresAt ? (now > o.expiresAt) : ((now - (o.createdAt || 0)) > 15 * 60000));
          if (isExp) {
            o.status = "expired";
            changed = true;
            ctx.waitUntil(cancelPakasirTransaction(o.orderId, o.amount, o.txnId));
            ctx.waitUntil((async () => {
              try {
                const s = await safeKvGet(kv, `order:${o.orderId}`);
                if (s) {
                  const rec = JSON.parse(s);
                  if (rec.status === "pending") {
                    rec.status = "expired";
                    await safeKvPut(kv, `order:${o.orderId}`, JSON.stringify(rec), { expirationTtl: 86400 });
                  }
                }
              } catch (_) {}
            })());
          }
        }
        if (changed) {
          ctx.waitUntil(safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 }));
        }
        return jsonResponse({ success: true, orders: devOrders }, 200, 10);
      }

      if (path === "/api/order/status" || path === "/api/orders/status") {
        const orderId = (url.searchParams.get("orderId") || url.searchParams.get("order_id") || "").trim();
        if (!orderId || !isValidOrderId(orderId)) return jsonResponse({ error: "valid orderId required" }, 400);

        let orderRecord = getMemOrder(orderId);
        if (!orderRecord) {
          const orderRecordStr = await safeKvGet(kv, `order:${orderId}`);
          if (orderRecordStr) {
            try { orderRecord = JSON.parse(orderRecordStr); } catch (_) {}
          }
        }

        const slug = env.PAKASIR_SLUG || env.PAKASIR_PROJECT;
        const apiKey = env.PAKASIR_API_KEY;
        const queryDeviceId = normalizeDeviceId(url.searchParams.get("deviceId") || url.searchParams.get("device_id") || "");


        if (!orderRecord && slug && apiKey && isValidDeviceId(queryDeviceId)) {
          try {
            let pData = null;
            const detailUrl = `https://app.pakasir.com/api/transactiondetail?project=${encodeURIComponent(slug)}&amount=0&order_id=${encodeURIComponent(orderId)}&api_key=${encodeURIComponent(apiKey)}`;
            const dResp = await fetch(detailUrl);
            if (dResp.ok) {
              const dJson = await dResp.json();
              pData = dJson.transaction || dJson.payment || dJson;
            }

            const completedTs = pData ? Date.parse(pData.completed_at || pData.paid_at || "") : NaN;
            const isRecent = !Number.isFinite(completedTs) || (Date.now() - completedTs) < 3 * 86400000;
            if (pData && isRecent && String(pData.status).toLowerCase().trim() === "completed") {
              const isCreditPack = orderId.startsWith("CRD-");
              let planId = "1_month";
              let creditAmount = 0;
              if (isCreditPack) {
                const parts = orderId.split("-");
                if (parts[1]) {
                  planId = parts[1].toLowerCase();
                  creditAmount = CREDIT_PACKS[planId]?.credits || parseInt(planId.replace("credits_", ""), 10) || 10;
                }
              } else if (orderId.startsWith("GN-")) {
                const parts = orderId.split("-");
                if (parts[1]) planId = parts[1].toLowerCase();
              }

              orderRecord = {
                orderId,
                txnId: pData.txn_id || null,
                deviceId: queryDeviceId || "",
                planId,
                isCreditPack,
                creditAmount,
                amount: Number(pData.amount || pData.total_payment || 5000),
                fee: 0,
                totalPayment: Number(pData.total_payment || pData.amount || 5000),
                paymentMethod: pData.method || "qris",
                status: "completed",
                paidAt: Date.now(),
                createdAt: Date.now() - 60000,
                expiresAt: Date.now() + 86400000,
                verifiedVia: "pakasir_dynamic_recovery"
              };

              setMemOrder(orderId, orderRecord);
              await safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 365 * 86400 });
            }
          } catch (_) {}
        }

        if (!orderRecord) return jsonResponse({ success: false, status: "not_found" }, 404);
        const now = Date.now();

        const recentlyExpired = orderRecord.status === "expired" && (now - (orderRecord.createdAt || 0)) < 86400000;
        if ((orderRecord.status === "pending" || recentlyExpired) && slug && apiKey) {
          try {
            let dData = null;
            if (orderRecord.txnId) {
              const v2StatusUrl = `https://app.pakasir.com/api/v2/transaction-status/${encodeURIComponent(slug)}/${encodeURIComponent(orderRecord.txnId)}`;
              const v2Resp = await fetch(v2StatusUrl, {
                headers: { "X-Api-Key": apiKey }
              });
              if (v2Resp.ok) {
                const v2Json = await v2Resp.json();
                dData = v2Json.data || v2Json.transaction || v2Json;
              }
            }

            if (!dData) {
              const detailUrl = `https://app.pakasir.com/api/transactiondetail?project=${encodeURIComponent(slug)}&amount=${orderRecord.amount}&order_id=${encodeURIComponent(orderId)}&api_key=${encodeURIComponent(apiKey)}`;
              const dResp = await fetch(detailUrl);
              if (dResp.ok) {
                const dJson = await dResp.json();
                dData = dJson.transaction || dJson.payment || dJson;
              }
            }

            if (dData && dData.status === "completed") {
              orderRecord.status = "completed";
              orderRecord.paidAt = now;
              orderRecord.verifiedVia = "pakasir_status_sync";
              setMemOrder(orderId, orderRecord);
              await safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 365 * 86400 });

              const targetDevId = orderRecord.deviceId || queryDeviceId;
              if (targetDevId) {
                orderRecord.deviceId = targetDevId;
                await applyCompletedOrderToDevice(targetDevId, orderRecord);
              }

              ctx.waitUntil((async () => {
                try {
                  let allOrders = await getIndexedOrders();
                  for (const ord of allOrders) {
                    if (ord.orderId === orderId) ord.status = "completed";
                  }
                  await saveIndexedOrders(allOrders);
                } catch (_) {}
              })());

              if (targetDevId) {
                try {
                  let devOrders = (await safeKvGet(kv, `device_orders:${targetDevId}`, "json")) || [];
                  const existIdx = devOrders.findIndex(o => o.orderId === orderId);
                  if (existIdx >= 0) {
                    devOrders[existIdx].status = "completed";
                  } else {
                    devOrders.unshift({
                      orderId: orderRecord.orderId,
                      txnId: orderRecord.txnId,
                      planId: orderRecord.planId,
                      planName: orderRecord.planName || orderRecord.planId,
                      isCreditPack: !!orderRecord.isCreditPack,
                      creditAmount: orderRecord.creditAmount || (CREDIT_PACKS[orderRecord.planId]?.credits || 0),
                      amount: orderRecord.amount,
                      totalPayment: orderRecord.totalPayment,
                      paymentMethod: orderRecord.paymentMethod,
                      status: "completed",
                      createdAt: orderRecord.createdAt,
                      expiresAt: orderRecord.expiresAt
                    });
                  }
                  await safeKvPut(kv, `device_orders:${targetDevId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 });
                } catch (_) {}
              }
            } else if (dData && (dData.status === "expired" || dData.status === "cancelled")) {
              orderRecord.status = dData.status;
              setMemOrder(orderId, orderRecord);
              await safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 86400 });
            }
          } catch (_) {}
        }

        const targetDevId = orderRecord.deviceId || queryDeviceId;
        if (orderRecord.status === "completed" && targetDevId) {
          orderRecord.deviceId = targetDevId;
          await applyCompletedOrderToDevice(targetDevId, orderRecord);
        }

        let currentCreditBalance = 0;
        let currentDevExp = 0;
        if (targetDevId) {
          const dev = getMemDevice(targetDevId) || (await safeKvGet(kv, `device:${targetDevId}`, "json"));
          if (dev) {
            currentCreditBalance = Number(dev.creditBalance || 0);
            currentDevExp = Number(dev.expiresAt || 0);
          }
        }

        const isExp = orderRecord.status === "pending" && (orderRecord.expiresAt ? (now > orderRecord.expiresAt) : ((now - (orderRecord.createdAt || 0)) > 15 * 60000));
        if (isExp) {
          orderRecord.status = "expired";
          setMemOrder(orderId, orderRecord);
          await safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 86400 });
          ctx.waitUntil(cancelPakasirTransaction(orderId, orderRecord.amount, orderRecord.txnId));

          ctx.waitUntil((async () => {
            try {
              let allOrders = await getIndexedOrders();
              let idxChanged = false;
              for (const ord of allOrders) {
                if (ord.orderId === orderId && ord.status === "pending") {
                  ord.status = "expired";
                  idxChanged = true;
                }
              }
              if (idxChanged) await saveIndexedOrders(allOrders);
            } catch (_) {}
          })());

          if (orderRecord.deviceId) {
            try {
              let devOrders = (await safeKvGet(kv, `device_orders:${orderRecord.deviceId}`, "json")) || [];
              let devChanged = false;
              for (const o of devOrders) {
                if (o.orderId === orderId && o.status === "pending") {
                  o.status = "expired";
                  devChanged = true;
                }
              }
              if (devChanged) {
                await safeKvPut(kv, `device_orders:${orderRecord.deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 });
              }
            } catch (_) {}
          }
        }

        return jsonResponse({
          success: true,
          status: orderRecord.status,
          orderId: orderRecord.orderId,
          deviceId: targetDevId || orderRecord.deviceId,
          creditBalance: currentCreditBalance,
          deviceExpiresAt: currentDevExp,
          txnId: orderRecord.txnId || null,
          planId: orderRecord.planId,
          totalPayment: orderRecord.totalPayment,
          paymentMethod: orderRecord.paymentMethod,
          paymentLink: orderRecord.paymentLink || null,
          vaNumber: orderRecord.vaNumber || null,
          vaBank: orderRecord.vaBank || null,
          qrString: orderRecord.qrisString || orderRecord.qrString || null,
          createdAt: orderRecord.createdAt,
          expiresAt: orderRecord.expiresAt
        }, 200, 0);
      }

      if ((path === "/api/order/cancel" || path === "/api/orders/cancel") && request.method === "POST") {
        const body = await safeJson(request);
        const orderId = (body.orderId || body.order_id || "").trim();
        const deviceId = (body.deviceId || body.device_id || "").trim();
        if (!orderId) return jsonResponse({ error: "orderId required" }, 400);

        let orderRecord = getMemOrder(orderId);
        if (!orderRecord) {
          const orderRecordStr = await safeKvGet(kv, `order:${orderId}`);
          if (orderRecordStr) {
            try { orderRecord = JSON.parse(orderRecordStr); } catch (_) {}
          }
        }

        if (orderRecord && orderRecord.status === "pending") {
          orderRecord.status = "cancelled";
          orderRecord.cancelledAt = Date.now();
          setMemOrder(orderId, orderRecord);
          ctx.waitUntil(safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 86400 }));
          ctx.waitUntil(cancelPakasirTransaction(orderId, orderRecord.amount, orderRecord.txnId));
        }

        ctx.waitUntil((async () => {
          try {
            let allOrders = await getIndexedOrders();
            for (const ord of allOrders) {
              if (ord.orderId === orderId) ord.status = "cancelled";
            }
            await saveIndexedOrders(allOrders);
          } catch (_) {}
        })());

        if (deviceId) {
          try {
            let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
            for (const o of devOrders) {
              if (o.orderId === orderId && o.status === "pending") {
                o.status = "cancelled";
              }
            }
            ctx.waitUntil(safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 }));
          } catch (_) {}
        }

        return jsonResponse({ success: true, message: "Order cancelled" });
      }

      if (path === "/api/credits/consume" && request.method === "POST") {
        const body = await safeJson(request);
        const deviceId = normalizeDeviceId(body.deviceId || body.device_id || "");
        const action = (body.action || "boost_session").trim();
        const consumeToken = String(body.consumeToken || body.consume_token || "").trim().slice(0, 64);

        if (!isValidDeviceId(deviceId)) {
          return jsonResponse({ success: false, error: "device_id_required", message: "deviceId tidak valid" }, 400);
        }

        const redeemConfig = CREDIT_REDEEM_ACTIONS[action];
        if (!redeemConfig) {
          return jsonResponse({
            success: false,
            error: "invalid_action",
            message: `Action '${action}' tidak dikenal`,
            validActions: Object.keys(CREDIT_REDEEM_ACTIONS)
          }, 400);
        }

        if (consumeToken) {
          const cached = await safeKvGet(kv, `consume_idem:${consumeToken}`, "json");
          if (cached && cached.deviceId === deviceId) {
            return jsonResponse({ success: true, ...cached.result, idempotent: true });
          }
        }

        const now = Date.now();
        const lastTs = MEM_CONSUME_GUARD.get(deviceId) || 0;
        if (now - lastTs < 1500) {
          return jsonResponse({ success: false, error: "request_too_fast", message: "Tunggu sebentar sebelum request berikutnya" }, 429);
        }
        MEM_CONSUME_GUARD.set(deviceId, now);
        if (MEM_CONSUME_GUARD.size > 2000) MEM_CONSUME_GUARD.delete(MEM_CONSUME_GUARD.keys().next().value);

        // 1. Fetch device state from RAM cache or KV
        let data = getMemDevice(deviceId);
        if (!data) {
          data = await safeKvGet(kv, `device:${deviceId}`, "json");
          if (data) setMemDevice(deviceId, data);
        }

        // 2. Load order history for reconciliation and self-healing
        let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
        let totalCompletedCredits = 0;
        let totalConsumedFromOrders = 0;

        for (const o of devOrders) {
          if (o.status === "completed" || o.status === "paid") {
            const isCrd = !!(o.isCreditPack || (o.planId && String(o.planId).startsWith("credits_")) || CREDIT_PACKS[o.planId]);
            if (isCrd) {
              const packCredits = Number(o.creditAmount || CREDIT_PACKS[o.planId]?.credits || (parseInt(String(o.planId).replace("credits_", ""), 10) || 0) || 0);
              totalCompletedCredits += packCredits;
            }
          } else if (o.status === "consumed" && o.creditsDeducted) {
            totalConsumedFromOrders += Number(o.creditsDeducted) || 0;
          }
        }

        // 3. Self-healing: if device record is missing or wiped in KV, reconstruct from devOrders
        if (!data) {
          if (totalCompletedCredits > 0) {
            const calculatedBalance = Math.max(0, totalCompletedCredits - totalConsumedFromOrders);
            data = {
              deviceId,
              planId: "credit_wallet",
              planName: "Credit Wallet",
              expiresAt: 0,
              creditBalance: calculatedBalance,
              totalPurchased: totalCompletedCredits,
              totalConsumed: totalConsumedFromOrders,
              appliedOrders: devOrders.filter(o => o.status === "completed" || o.status === "paid").map(o => o.orderId),
              consumeHistory: [],
              createdAt: now,
              updatedAt: now,
              source: "consume_auto_reconstructed"
            };
            setMemDevice(deviceId, data);
            await safeKvPut(kv, `device:${deviceId}`, JSON.stringify(data));
          } else {
            return jsonResponse({
              success: false,
              error: "insufficient_credits",
              message: "Kredit tidak mencukupi (0 kredit). Silakan beli paket kredit terlebih dahulu.",
              required: redeemConfig.credits,
              creditBalance: 0
            }, 400);
          }
        } else {
          // Reconcile: check if devOrders has completed credit orders not yet credited in appliedOrders
          if (!Array.isArray(data.appliedOrders)) data.appliedOrders = [];
          let newlyAdded = 0;
          for (const o of devOrders) {
            if ((o.status === "completed" || o.status === "paid") && o.orderId && !data.appliedOrders.includes(o.orderId)) {
              const isCrd = !!(o.isCreditPack || (o.planId && String(o.planId).startsWith("credits_")) || CREDIT_PACKS[o.planId]);
              if (isCrd) {
                const packCredits = Number(o.creditAmount || CREDIT_PACKS[o.planId]?.credits || (parseInt(String(o.planId).replace("credits_", ""), 10) || 0) || 0);
                newlyAdded += packCredits;
                data.appliedOrders.push(o.orderId);
              }
            }
          }
          if (newlyAdded > 0) {
            data.creditBalance = (Number(data.creditBalance) || 0) + newlyAdded;
            data.totalPurchased = (Number(data.totalPurchased) || 0) + newlyAdded;
            data.updatedAt = now;
          }
        }

        const currentCredits = Math.max(0, Number(data.creditBalance || 0));
        const required = redeemConfig.credits;

        if (currentCredits < required) {
          return jsonResponse({
            success: false,
            error: "insufficient_credits",
            message: `Kredit tidak cukup. Butuh ${required} kredit, saldo Anda: ${currentCredits}`,
            required,
            creditBalance: currentCredits
          }, 400);
        }

        const newBalance = currentCredits - required;
        const prevExp = Number(data.expiresAt || 0);
        const sessionBase = prevExp > now ? prevExp : now;
        const newExpiresAt = sessionBase + redeemConfig.durationMs;

        data.creditBalance = newBalance;
        data.totalConsumed = (Number(data.totalConsumed) || 0) + required;
        data.expiresAt = newExpiresAt;
        data.planId = "credit_session";
        data.planName = redeemConfig.name;
        data.updatedAt = now;
        data.lastConsumeAction = action;
        data.lastConsumeAt = now;

        if (!Array.isArray(data.consumeHistory)) data.consumeHistory = [];
        data.consumeHistory.unshift({
          action,
          actionName: redeemConfig.name,
          credits: required,
          consumedAt: now,
          expiresAt: newExpiresAt
        });
        if (data.consumeHistory.length > 50) data.consumeHistory = data.consumeHistory.slice(0, 50);

        // Immediate write to memory cache and KV
        setMemDevice(deviceId, data);
        await safeKvPut(kv, `device:${deviceId}`, JSON.stringify(data));

        // Append durable ledger entry into device_orders
        try {
          devOrders.unshift({
            orderId: `USE-${action.toUpperCase()}-${Date.now().toString().slice(-6)}`,
            planId: action,
            planName: `Used ${required} Credit: ${redeemConfig.name}`,
            isCreditPack: false,
            isConsumeRecord: true,
            creditsDeducted: required,
            status: "consumed",
            createdAt: now,
            expiresAt: newExpiresAt
          });
          if (devOrders.length > 50) devOrders = devOrders.slice(0, 50);
          await safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 });
        } catch (_) {}

        ctx.waitUntil((async () => {
          try {
            let devs = await getIndexedDevices();
            const idx = devs.findIndex(d => d.deviceId === deviceId);
            if (idx >= 0) devs[idx] = data; else devs.unshift(data);
            await saveIndexedDevices(devs);
          } catch (_) {}
        })());

        const result = {
          action: redeemConfig.action,
          actionName: redeemConfig.name,
          creditsDeducted: required,
          creditBalance: newBalance,
          expiresAt: newExpiresAt,
          sessionPassGranted: true,
          remainingSeconds: Math.max(0, Math.floor((newExpiresAt - now) / 1000))
        };

        if (consumeToken) {
          ctx.waitUntil(safeKvPut(kv, `consume_idem:${consumeToken}`, JSON.stringify({ deviceId, result }), { expirationTtl: 3600 }));
        }

        return jsonResponse({ success: true, ...result });
      }

      if (path === "/api/credits/balance" && request.method === "GET") {
        const deviceId = normalizeDeviceId(url.searchParams.get("deviceId") || url.searchParams.get("device_id") || "");
        if (!isValidDeviceId(deviceId)) {
          return jsonResponse({ error: "valid deviceId required" }, 400);
        }
        let data = getMemDevice(deviceId);
        if (!data) {
          data = await safeKvGet(kv, `device:${deviceId}`, "json");
          if (data) setMemDevice(deviceId, data);
        }
        let balance = data ? Math.max(0, Number(data.creditBalance || 0)) : 0;
        if (!data) {
          const devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
          let bought = 0, used = 0;
          for (const o of devOrders) {
            if (o.status === "completed" || o.status === "paid") {
              const isCrd = !!(o.isCreditPack || (o.planId && String(o.planId).startsWith("credits_")) || CREDIT_PACKS[o.planId]);
              if (isCrd) bought += Number(o.creditAmount || CREDIT_PACKS[o.planId]?.credits || (parseInt(String(o.planId).replace("credits_", ""), 10) || 0) || 0);
            } else if (o.status === "consumed" && o.creditsDeducted) {
              used += Number(o.creditsDeducted) || 0;
            }
          }
          balance = Math.max(0, bought - used);
        }
        return jsonResponse({ success: true, deviceId, creditBalance: balance }, 200, 0);
      }

      if ((path === "/webhook" || path === "/api/webhook" || path === "/api/webhook/pakasir") && request.method === "POST") {
        const secretHeader = (request.headers.get("X-Secret") || request.headers.get("x-secret") || "").trim();
        if (env.PAKASIR_WEBHOOK_SECRET && secretHeader && secretHeader !== env.PAKASIR_WEBHOOK_SECRET) {
          return jsonResponse({ error: "Invalid webhook secret" }, 401);
        }

        const body = await safeJson(request);
        let orderId = (body.order_id || body.orderId || "").trim();
        const txnId = (body.txn_id || body.txnId || "").trim();

        if (!orderId && txnId) {
          const mappedOrderId = await safeKvGet(kv, `txn:${txnId}`);
          if (mappedOrderId) orderId = mappedOrderId.trim();
        }

        if (!orderId) return jsonResponse({ error: "order_id missing" }, 400);

        let orderRecord = getMemOrder(orderId);
        if (!orderRecord) {
          const orderRecordStr = await safeKvGet(kv, `order:${orderId}`);
          if (orderRecordStr) {
            try { orderRecord = JSON.parse(orderRecordStr); } catch (_) {}
          }
        }

        if (!orderRecord) return jsonResponse({ error: "order not found" }, 404);


        if (orderRecord.status === "completed") {
          return jsonResponse({ success: true, message: "Order already completed" });
        }

        const slug = env.PAKASIR_SLUG || env.PAKASIR_PROJECT;
        const apiKey = env.PAKASIR_API_KEY;
        if (!slug || !apiKey) {
          return jsonResponse({ error: "Gateway credentials not configured on server" }, 500);
        }

        let verifyData = null;
        const activeTxnId = txnId || orderRecord.txnId;

        if (activeTxnId) {
          try {

            const v2StatusUrl = `https://app.pakasir.com/api/v2/transaction-status/${encodeURIComponent(slug)}/${encodeURIComponent(activeTxnId)}`;
            const verifyResp = await fetch(v2StatusUrl, {
              headers: { "X-Api-Key": apiKey }
            });
            if (verifyResp.ok) {
              const v2Json = await verifyResp.json();
              verifyData = v2Json.data || v2Json.transaction || v2Json;
            }
          } catch (_) {}
        }

        if (!verifyData) {
          try {
            const verifyUrl = `https://app.pakasir.com/api/transactiondetail?project=${encodeURIComponent(slug)}&amount=${orderRecord.amount}&order_id=${encodeURIComponent(orderId)}&api_key=${encodeURIComponent(apiKey)}`;
            const verifyResp = await fetch(verifyUrl);
            if (verifyResp.ok) {
              const v1Json = await verifyResp.json();
              verifyData = v1Json.transaction || v1Json.payment || v1Json;
            }
          } catch (err) {
            return jsonResponse({ error: "Failed to connect to Pakasir verification API", details: err.message }, 502);
          }
        }

        const txData = verifyData;
        if (!txData || String(txData.status).toLowerCase().trim() !== "completed") {
          return jsonResponse({
            error: "Fraudulent or unverified payment attempt rejected",
            reason: "Pakasir official record does not confirm completion",
            gatewayStatus: txData?.status || "unverified"
          }, 403);
        }

        const receivedAmount = Number(txData.amount || txData.total_payment || body.amount || 0);
        const minExpected = Math.min(orderRecord.amount, orderRecord.totalPayment);
        if (receivedAmount > 0 && receivedAmount < minExpected) {
          return jsonResponse({ error: "underpaid", expected: minExpected, received: receivedAmount }, 400);
        }

        orderRecord.status = "completed";
        orderRecord.paidAt = Date.now();
        orderRecord.verifiedVia = "pakasir_v2_webhook";
        if (activeTxnId && !orderRecord.txnId) {
          orderRecord.txnId = activeTxnId;
        }
        setMemOrder(orderId, orderRecord);
        await safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 365 * 86400 });

        if (orderRecord.deviceId) {
          await applyCompletedOrderToDevice(orderRecord.deviceId, orderRecord);
        }

        ctx.waitUntil((async () => {
          try {
            let allOrders = await getIndexedOrders();
            for (const ord of allOrders) {
              if (ord.orderId === orderId) ord.status = "completed";
            }
            await saveIndexedOrders(allOrders);
          } catch (_) {}
        })());

        try {
          let devOrders = (await safeKvGet(kv, `device_orders:${orderRecord.deviceId}`, "json")) || [];
          const existIdx = devOrders.findIndex(o => o.orderId === orderId);
          if (existIdx >= 0) {
            devOrders[existIdx].status = "completed";
          } else {
            devOrders.unshift({
              orderId,
              txnId: activeTxnId,
              planId: orderRecord.planId,
              planName: orderRecord.planName || orderRecord.planId,
              isCreditPack: !!(orderRecord.isCreditPack || CREDIT_PACKS[orderRecord.planId]),
              creditAmount: orderRecord.creditAmount || (CREDIT_PACKS[orderRecord.planId]?.credits || 0),
              amount: orderRecord.amount,
              totalPayment: orderRecord.totalPayment,
              paymentMethod: orderRecord.paymentMethod,
              status: "completed",
              createdAt: orderRecord.createdAt,
              expiresAt: orderRecord.expiresAt
            });
          }
          if (devOrders.length > 50) devOrders = devOrders.slice(0, 50);
          await safeKvPut(kv, `device_orders:${orderRecord.deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 });
        } catch (_) {}

        return jsonResponse({ success: true, message: "Subscription activated" });
      }

      if (path === "/api/admin/login" && request.method === "POST") {
        const body = await safeJson(request);
        const user = (body.username || "").trim();
        const pass = (body.password || "").trim();

        const adminPass = env.ADMIN_PASS || ADMIN_PASS;
        if (user === ADMIN_USER && safeEqual(pass, adminPass)) {
          const ts = Date.now();
          const sig = await hmacHex(env.ADMIN_SECRET || ADMIN_SECRET, `${ADMIN_USER}:${ts}`);
          const token = btoa(`${ADMIN_USER}:${ts}:${sig}`);
          return jsonResponse({
            success: true,
            token,
            user: { username: ADMIN_USER, role: "Enterprise Admin" }
          });
        }
        return jsonResponse({ success: false, error: "Invalid credentials" }, 401);
      }

      if (path.startsWith("/api/admin/")) {
        const auth = request.headers.get("Authorization") || request.headers.get("X-Admin-Token") || "";
        const cleanToken = auth.replace(/^Bearer\s+/i, "").trim();
        let authorized = false;

        try {
          if (cleanToken) {
            const decoded = atob(cleanToken);
            const parts = decoded.split(":");
            if (parts.length === 3 && parts[0] === ADMIN_USER) {
              const tokenTs = Number(parts[1]) || 0;
              if (tokenTs > 0 && (Date.now() - tokenTs) < 7 * 86400 * 1000) {
                const expected = await hmacHex(env.ADMIN_SECRET || ADMIN_SECRET, `${ADMIN_USER}:${tokenTs}`);
                if (safeEqual(parts[2], expected)) authorized = true;
              }
            }
          }
        } catch (_) {}

        if (!authorized) return jsonResponse({ error: "Unauthorized access or expired session" }, 401);

        if (path === "/api/admin/stats" && request.method === "GET") {
          const now = Date.now();
          if (MEM_STATS_CACHE && (now - MEM_STATS_TS < 30000)) {
            return jsonResponse({ success: true, stats: MEM_STATS_CACHE });
          }

          const mode = (await safeKvGet(kv, "config:mode")) || "production";
          const devices = await getIndexedDevices();
          const orders = await getIndexedOrders();

          let activeVip = 0;
          for (const d of devices) {
            if (d && d.expiresAt > now) {
              activeVip++;
            }
          }

          let totalRevenue = 0;
          let pendingOrders = 0;
          let completedOrders = 0;
          let expiredOrders = 0;

          for (const o of orders) {
            if (o) {
              const st = o.status || "pending";
              if (st === "completed" || st === "paid") {
                completedOrders++;
                totalRevenue += Number(o.totalPayment || o.amount || 0);
              } else if (st === "pending") {
                pendingOrders++;
              } else if (st === "expired") {
                expiredOrders++;
              }
            }
          }

          const stats = {
            activeVip,
            totalDevices: devices.length,
            totalOrders: orders.length,
            totalRevenue,
            pendingOrders,
            completedOrders,
            expiredOrders,
            gatewayMode: mode,
            uptime: "99.99%",
            serverTime: now
          };

          MEM_STATS_CACHE = stats;
          MEM_STATS_TS = now;
          return jsonResponse({ success: true, stats });
        }

        if (path === "/api/admin/devices") {
          if (request.method === "GET") {
            const queryDev = normalizeDeviceId(url.searchParams.get("deviceId") || "");
            if (queryDev) {
              const dev = getMemDevice(queryDev) || (await safeKvGet(kv, `device:${queryDev}`, "json"));
              return jsonResponse({ success: true, device: dev });
            }
            const devices = await getIndexedDevices();
            return jsonResponse({ success: true, devices });
          }

          if (request.method === "POST") {
            const body = await safeJson(request);
            const deviceId = normalizeDeviceId(body.deviceId || "");
            const days = Number(body.days || 30);
            const action = (body.action || "extend").toLowerCase().trim();
            const planId = body.planId || (days >= 999 ? "VIP_LIFETIME" : (days >= 365 ? "VIP_ANNUAL" : "VIP_ADMIN"));

            if (!deviceId) return jsonResponse({ error: "deviceId required" }, 400);

            const now = Date.now();
            let existing = getMemDevice(deviceId) || (await safeKvGet(kv, `device:${deviceId}`, "json")) || {};
            const prevExp = Number(existing.expiresAt || existing.expires_at || 0);

            let newExpiresAt = prevExp;
            if (action === "revoke") {
              newExpiresAt = now - 1000;
            } else if (action === "set") {
              newExpiresAt = now + (days * 86400000);
            } else {
              const currentExp = prevExp > now ? prevExp : now;
              newExpiresAt = currentExp + (days * 86400000);
            }

            const currentCredits = body.credits !== undefined ? Number(body.credits) : Number(existing.creditBalance || 0);

            const devData = {
              ...existing,
              deviceId,
              planId,
              planName: action === "revoke" ? "Revoked" : (days >= 999 ? "Lifetime VIP Pass" : `${days} Days VIP Pass`),
              expiresAt: newExpiresAt,
              creditBalance: Math.max(0, currentCredits),
              totalPurchased: Number(existing.totalPurchased || 0),
              totalConsumed: Number(existing.totalConsumed || 0),
              appliedOrders: Array.isArray(existing.appliedOrders) ? existing.appliedOrders : [],
              updatedAt: now,
              grantedBy: "admin"
            };

            setMemDevice(deviceId, devData);
            await safeKvPut(kv, `device:${deviceId}`, JSON.stringify(devData));

            let devs = await getIndexedDevices();
            const idx = devs.findIndex(d => d.deviceId === deviceId);
            if (idx >= 0) devs[idx] = devData; else devs.unshift(devData);
            await saveIndexedDevices(devs);

            try {
              let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
              devOrders.unshift({
                orderId: `GN-ADMIN-${Date.now().toString().slice(-6)}`,
                planId,
                planName: action === "revoke" ? "VIP Access Revoked by Admin" : (days >= 999 ? "Lifetime VIP Pass (Admin Grant)" : `${days} Days VIP Pass (Admin Grant)`),
                amount: 0,
                fee: 0,
                totalPayment: 0,
                paymentMethod: "admin_grant",
                status: action === "revoke" ? "revoked" : "completed",
                createdAt: now,
                expiresAt: newExpiresAt
              });
              if (devOrders.length > 20) devOrders = devOrders.slice(0, 20);
              await safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 });
            } catch (_) {}

            return jsonResponse({ success: true, device: devData });
          }

          if (request.method === "DELETE") {
            const deviceId = (url.searchParams.get("deviceId") || "").trim();
            if (!deviceId) return jsonResponse({ error: "deviceId required" }, 400);

            await safeKvDelete(kv, `device:${deviceId}`);
            await safeKvDelete(kv, `device_orders:${deviceId}`);
            delMemDevice(deviceId);

            let devs = await getIndexedDevices();
            devs = devs.filter(d => d.deviceId !== deviceId);
            await saveIndexedDevices(devs);

            return jsonResponse({ success: true, message: `Device ${deviceId} removed` });
          }
        }

        if (path === "/api/admin/devices/credits" && request.method === "POST") {
          const body = await safeJson(request);
          const deviceId = normalizeDeviceId(body.deviceId || "");
          if (!deviceId) return jsonResponse({ error: "deviceId required" }, 400);

          const action = (body.action || "add").toLowerCase().trim(); // "add", "set", "deduct"
          const amount = Math.min(100000, Math.max(0, Math.floor(Number(body.amount) || 0)));
          const reason = String(body.reason || "Admin Credit Adjustment").trim().slice(0, 120);

          const now = Date.now();
          let existing = getMemDevice(deviceId) || (await safeKvGet(kv, `device:${deviceId}`, "json")) || {};
          const prevCredits = Number(existing.creditBalance || 0);
          let newCredits = prevCredits;

          if (action === "set") {
            newCredits = Math.max(0, amount);
          } else if (action === "deduct") {
            newCredits = Math.max(0, prevCredits - amount);
          } else {
            newCredits = prevCredits + Math.max(0, amount);
          }

          const devData = {
            ...existing,
            deviceId,
            planId: existing.planId || "credit_wallet",
            planName: existing.planName || "Credit Wallet",
            creditBalance: newCredits,
            totalPurchased: action === "add" ? ((Number(existing.totalPurchased) || 0) + amount) : Number(existing.totalPurchased || 0),
            totalConsumed: action === "deduct" ? ((Number(existing.totalConsumed) || 0) + amount) : Number(existing.totalConsumed || 0),
            appliedOrders: Array.isArray(existing.appliedOrders) ? existing.appliedOrders : [],
            updatedAt: now,
            grantedBy: "admin_credits"
          };

          setMemDevice(deviceId, devData);
          await safeKvPut(kv, `device:${deviceId}`, JSON.stringify(devData));

          let devs = await getIndexedDevices();
          const idx = devs.findIndex(d => d.deviceId === deviceId);
          if (idx >= 0) devs[idx] = devData; else devs.unshift(devData);
          await saveIndexedDevices(devs);

          try {
            let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
            devOrders.unshift({
              orderId: `CRD-ADMIN-${Date.now().toString().slice(-6)}`,
              planId: `credits_admin_${action}`,
              planName: `Credit ${action.toUpperCase()}: ${amount} Credits (${reason})`,
              isCreditPack: true,
              creditAmount: amount,
              amount: 0,
              fee: 0,
              totalPayment: 0,
              paymentMethod: "admin_credits",
              status: "completed",
              createdAt: now,
              expiresAt: now + 365 * 86400000
            });
            if (devOrders.length > 20) devOrders = devOrders.slice(0, 20);
            await safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 });
          } catch (_) {}

          return jsonResponse({
            success: true,
            deviceId,
            action,
            amount,
            previousCredits: prevCredits,
            creditBalance: newCredits,
            device: devData
          });
        }

        if (path === "/api/admin/orders") {
          if (request.method === "GET") {
            const orders = await getIndexedOrders();
            orders.sort((a, b) => b.createdAt - a.createdAt);
            return jsonResponse({ success: true, orders });
          }

          if (request.method === "PUT") {
            const body = await safeJson(request);
            const orderId = (body.orderId || "").trim();
            const newStatus = (body.status || "").toLowerCase().trim();

            if (!orderId) return jsonResponse({ error: "orderId required" }, 400);

            let orderRecord = getMemOrder(orderId);
            if (!orderRecord) {
              const orderStr = await safeKvGet(kv, `order:${orderId}`);
              if (orderStr) {
                try { orderRecord = JSON.parse(orderStr); } catch (_) {}
              }
            }

            if (!orderRecord) return jsonResponse({ error: "order not found" }, 404);

            orderRecord.status = newStatus;
            setMemOrder(orderId, orderRecord);
            await safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 86400 });

            let allOrders = await getIndexedOrders();
            for (const ord of allOrders) {
              if (ord.orderId === orderId) ord.status = newStatus;
            }
            await saveIndexedOrders(allOrders);

            if (orderRecord.deviceId) {
              try {
                let devOrders = (await safeKvGet(kv, `device_orders:${orderRecord.deviceId}`, "json")) || [];
                let devChanged = false;
                for (const o of devOrders) {
                  if (o.orderId === orderId) {
                    o.status = newStatus;
                    devChanged = true;
                  }
                }
                if (devChanged) {
                  await safeKvPut(kv, `device_orders:${orderRecord.deviceId}`, JSON.stringify(devOrders), { expirationTtl: 90 * 86400 });
                }
              } catch (_) {}
            }

            if (newStatus === "completed") {
              const plan = PLANS[orderRecord.planId] || PLANS["1_month"];
              const now = Date.now();
              const existingDev = (await safeKvGet(kv, `device:${orderRecord.deviceId}`, "json")) || {};
              const prevExp = existingDev.expiresAt || existingDev.expires_at || 0;
              const curExp = prevExp > now ? prevExp : now;
              const newExpiresAt = curExp + plan.durationMs;

              const devData = {
                deviceId: orderRecord.deviceId,
                planId: plan.id,
                expiresAt: newExpiresAt,
                updatedAt: now,
                grantedBy: "admin_override"
              };

              setMemDevice(orderRecord.deviceId, devData);
              await safeKvPut(kv, `device:${orderRecord.deviceId}`, JSON.stringify(devData));

              let devs = await getIndexedDevices();
              const idx = devs.findIndex(d => d.deviceId === orderRecord.deviceId);
              if (idx >= 0) devs[idx] = devData; else devs.unshift(devData);
              await saveIndexedDevices(devs);
            }

            return jsonResponse({ success: true, order: orderRecord });
          }

          if (request.method === "DELETE") {
            const orderId = (url.searchParams.get("orderId") || "").trim();
            if (!orderId) return jsonResponse({ error: "orderId required" }, 400);

            await safeKvDelete(kv, `order:${orderId}`);
            MEM_ORDER_RECORDS.delete(orderId);

            let allOrders = await getIndexedOrders();
            allOrders = allOrders.filter(o => o.orderId !== orderId);
            await saveIndexedOrders(allOrders);

            return jsonResponse({ success: true, message: `Order ${orderId} removed` });
          }
        }

        if (path === "/api/admin/orders/purge-inactive" && request.method === "POST") {
          let allOrders = await getIndexedOrders();
          const initialCount = allOrders.length;
          const activeOrders = allOrders.filter(o => o.status === "completed" || o.status === "pending");
          await saveIndexedOrders(activeOrders);
          return jsonResponse({
            success: true,
            purged: initialCount - activeOrders.length,
            remaining: activeOrders.length
          });
        }

        if (path === "/api/admin/orders/verify-pakasir" && request.method === "POST") {
          const body = await safeJson(request);
          const orderId = (body.orderId || "").trim();
          if (!orderId) return jsonResponse({ error: "orderId required" }, 400);

          let order = getMemOrder(orderId);
          if (!order) {
            const orderStr = await safeKvGet(kv, `order:${orderId}`);
            if (orderStr) {
              try { order = JSON.parse(orderStr); } catch (_) {}
            }
          }
          if (!order) return jsonResponse({ error: "Order not found" }, 404);

          const slug = env.PAKASIR_SLUG || env.PAKASIR_PROJECT;
          const apiKey = env.PAKASIR_API_KEY;
          if (!slug || !apiKey) {
            return jsonResponse({ error: "Pakasir credentials not configured" }, 500);
          }

          let vJson = null;
          if (order.txnId) {
            try {
              const v2StatusUrl = `https://app.pakasir.com/api/v2/transaction-status/${encodeURIComponent(slug)}/${encodeURIComponent(order.txnId)}`;
              const v2Resp = await fetch(v2StatusUrl, {
                headers: { "X-Api-Key": apiKey }
              });
              if (v2Resp.ok) {
                vJson = await v2Resp.json();
              }
            } catch (_) {}
          }

          if (!vJson) {
            const verifyUrl = `https://app.pakasir.com/api/transactiondetail?project=${encodeURIComponent(slug)}&amount=${order.amount}&order_id=${encodeURIComponent(orderId)}&api_key=${encodeURIComponent(apiKey)}`;
            const vResp = await fetch(verifyUrl);
            vJson = await vResp.json();
          }

          return jsonResponse({ success: true, apiVersion: order.txnId ? "v2" : "v1", pakasirData: vJson, localOrder: order });
        }

        if (path === "/api/admin/booster/features") {
          if (request.method === "GET") {
            const features = (await safeKvGet(kv, "config:booster_features", "json")) || DEFAULT_FEATURES;
            return jsonResponse({ success: true, features });
          }

          if (request.method === "PUT") {
            const body = await safeJson(request);
            const updated = {
              version: (body.version || 1) + 1,
              last_updated: new Date().toISOString().split("T")[0],
              disabled_components: body.disabled_components || [],
              disabled_panels: body.disabled_panels || [],
              component_overrides: body.component_overrides || DEFAULT_FEATURES.component_overrides,
              emergency_broadcast: body.emergency_broadcast || DEFAULT_FEATURES.emergency_broadcast
            };

            await safeKvPut(kv, "config:booster_features", JSON.stringify(updated));
            MEM_FEATURES_CACHE = updated;
            MEM_FEATURES_TS = Date.now();

            return jsonResponse({ success: true, features: updated });
          }
        }

        if (path === "/api/admin/settings") {
          if (request.method === "GET") {
            const mode = (await safeKvGet(kv, "config:mode")) || "production";
            return jsonResponse({
              success: true,
              settings: {
                gatewayMode: mode,
                pakasirConfigured: Boolean((env.PAKASIR_SLUG || env.PAKASIR_PROJECT) && env.PAKASIR_API_KEY),
                pakasirSlug: env.PAKASIR_SLUG || env.PAKASIR_PROJECT || null,
                apiVersion: "v2"
              }
            });
          }

          if (request.method === "PUT") {
            const body = await safeJson(request);
            if (body.gatewayMode) {
              await safeKvPut(kv, "config:mode", body.gatewayMode);
            }
            return jsonResponse({ success: true, message: "Settings saved" });
          }
        }
      }

      return jsonResponse({ error: "Endpoint not found" }, 404);
    } catch (err) {
      return jsonResponse({ error: err.message || "Internal Edge Error" }, 500);
    }
  }
};

function jsonResponse(data, status = 200, maxAge = 0) {
  const headers = {
    ...CORS_HEADERS,
    "Content-Type": "application/json; charset=UTF-8"
  };
  if (maxAge > 0) {
    headers["Cache-Control"] = `public, max-age=${maxAge}, stale-while-revalidate=${maxAge * 2}`;
  } else {
    headers["Cache-Control"] = "no-store, no-cache, must-revalidate";
  }
  return new Response(JSON.stringify(data), { status, headers });
}
