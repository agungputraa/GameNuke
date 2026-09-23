const ADMIN_USER = "Agung220903";
const ADMIN_PASS = "@Wanda025";
const ADMIN_SECRET = "GN_SECRET_9824_K9X_VIP";

const PLANS = {
  "1_week": { id: "1_week", name: "Weekly VIP Pass", durationDays: 7, durationMs: 7 * 86400000, price: 15000, badge: "Flexible Trial" },
  "1_month": { id: "1_month", name: "Monthly VIP Pass", durationDays: 30, durationMs: 30 * 86400000, price: 35000, badge: "Most Popular" },
  "6_months": { id: "6_months", name: "Semi-Annual VIP Pass", durationDays: 180, durationMs: 180 * 86400000, price: 160000, badge: "Save 25%" },
  "1_year": { id: "1_year", name: "Annual VIP Pass", durationDays: 365, durationMs: 365 * 86400000, price: 260000, badge: "Best Value" }
};

const TRIAL_CONFIG = {
  enabled: true,
  durationDays: 3,
  durationMs: 3 * 86400000,
  maxTrialsPerIp24h: 2
};

function calculatePakasirFee(amount, paymentMethod, bank) {
  const method = (paymentMethod || "qris").toLowerCase().trim();
  if (method === "qris") {
    if (amount <= 105000) {
      return Math.round(amount * 0.007) + 310;
    } else {
      return Math.round(amount * 0.01);
    }
  }
  const b = (bank || "").toLowerCase().trim();
  if (b === "artha_graha" || b === "sampoerna") {
    return 2000;
  }
  return 3500;
}

function getPakasirMethod(paymentMethod, bank) {
  const method = (paymentMethod || "qris").toLowerCase().trim();
  if (method === "qris") return "qris";

  const b = (bank || "atm_bersama").toLowerCase().trim();
  if (b.includes("bni")) return "bni_va";
  if (b.includes("bri")) return "bri_va";
  if (b.includes("cimb")) return "cimb_niaga_va";
  if (b.includes("permata")) return "permata_va";
  if (b.includes("maybank")) return "maybank_va";
  if (b.includes("sampoerna")) return "sampoerna_va";
  if (b.includes("bnc") || b.includes("neo")) return "bnc_va";
  if (b.includes("artha")) return "artha_graha_va";
  return "atm_bersama_va";
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
const MEM_IP_TRIALS = new Map();
const MEM_IP_TTL_MS = 3600000;

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

function getMemIpTrials(ip) {
  const item = MEM_IP_TRIALS.get(ip);
  if (!item) return 0;
  if (Date.now() - item.ts > MEM_IP_TTL_MS) {
    MEM_IP_TRIALS.delete(ip);
    return 0;
  }
  return item.count;
}

function setMemIpTrials(ip, count) {
  MEM_IP_TRIALS.set(ip, { ts: Date.now(), count });
  if (MEM_IP_TRIALS.size > 2000) {
    const firstKey = MEM_IP_TRIALS.keys().next().value;
    MEM_IP_TRIALS.delete(firstKey);
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

    async function cancelPakasirTransaction(orderId, amount) {
      if (!env.PAKASIR_PROJECT || !env.PAKASIR_API_KEY || !orderId) return false;
      try {
        const resp = await fetch("https://app.pakasir.com/api/transactioncancel", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            project: env.PAKASIR_PROJECT,
            order_id: orderId,
            amount: Number(amount) || 0,
            api_key: env.PAKASIR_API_KEY
          })
        });
        return resp.ok;
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
            ctx.waitUntil(cancelPakasirTransaction(o.orderId, o.amount));
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

    try {
      if (path === "/health") {
        return jsonResponse({
          status: "ok",
          timestamp: Date.now(),
          service: "Game Nuke Edge Engine v3.4.0-Nexus",
          region: request.cf?.colo || "EDGE"
        }, 200, 60);
      }

      if (path === "/api/plans") {
        return jsonResponse({ success: true, plans: Object.values(PLANS) }, 200, 300);
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
        const deviceId = (url.searchParams.get("deviceId") || url.searchParams.get("device_id") || "").trim();
        if (!deviceId || deviceId.length < 16 || deviceId.length > 64) {
          return jsonResponse({ error: "valid deviceId required (16-64 chars)" }, 400);
        }

        const now = Date.now();
        let data = getMemDevice(deviceId);
        if (!data) {
          data = await safeKvGet(kv, `device:${deviceId}`, "json");
          if (data) setMemDevice(deviceId, data);
        }

        if (!data && TRIAL_CONFIG.enabled) {
          const isValidId = /^[a-fA-F0-9_-]{16,64}$/.test(deviceId) &&
                            deviceId !== "9774d56d682e549c" &&
                            !/^(.)\1+$/.test(deviceId);

          const clientIp = request.headers.get("CF-Connecting-IP") || "0.0.0.0";
          let ipAllowed = true;

          if (isValidId && clientIp !== "0.0.0.0") {
            const memCount = getMemIpTrials(clientIp);
            if (memCount >= TRIAL_CONFIG.maxTrialsPerIp24h) {
              ipAllowed = false;
            } else {
              const ipKey = `trial_ip:${clientIp}`;
              const currentIpTrials = Number(await safeKvGet(kv, ipKey)) || 0;
              if (currentIpTrials >= TRIAL_CONFIG.maxTrialsPerIp24h) {
                ipAllowed = false;
                setMemIpTrials(clientIp, currentIpTrials);
              } else {
                setMemIpTrials(clientIp, currentIpTrials + 1);
                ctx.waitUntil(safeKvPut(kv, ipKey, String(currentIpTrials + 1), { expirationTtl: 86400 }));
              }
            }
          }

          if (isValidId && ipAllowed) {
            const trialExpiresAt = now + TRIAL_CONFIG.durationMs;
            data = {
              deviceId,
              planId: "3_days_trial",
              planName: "3-Day Free Trial",
              isTrial: true,
              trialUsed: true,
              trialStartedAt: now,
              expiresAt: trialExpiresAt,
              createdAt: now,
              updatedAt: now,
              registeredIp: clientIp,
              source: "auto_trial_grant"
            };

            setMemDevice(deviceId, data);
            ctx.waitUntil(safeKvPut(kv, `device:${deviceId}`, JSON.stringify(data)));

            ctx.waitUntil((async () => {
              try {
                let devs = await getIndexedDevices();
                const idx = devs.findIndex(d => d.deviceId === deviceId);
                if (idx >= 0) devs[idx] = data; else devs.unshift(data);
                await saveIndexedDevices(devs);
              } catch (_) {}
            })());
          }
        }

        if (!data || !data.expiresAt) {
          return jsonResponse({ isActive: false, expiresAt: 0, remainingDays: 0, remainingSeconds: 0 }, 200, 15);
        }

        const diff = data.expiresAt - now;
        const isActive = diff > 0;
        const remainingDays = isActive ? Math.ceil(diff / 86400000) : 0;
        const remainingSeconds = isActive ? Math.floor(diff / 1000) : 0;

        return jsonResponse({
          isActive,
          planId: data.planId || (data.isTrial ? "3_days_trial" : "VIP"),
          expiresAt: data.expiresAt,
          remainingDays,
          remainingSeconds,
          isTrial: !!data.isTrial
        }, 200, 15);
      }

      if (path === "/api/order/create" && request.method === "POST") {
        const body = await safeJson(request);
        const deviceId = (body.deviceId || "").trim();
        const planId = (body.planId || "1_month").trim();
        const paymentMethod = (body.paymentMethod || "qris").toLowerCase().trim();
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
              const targetPlan = PLANS[planId] || PLANS["1_month"];
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
                plan: { id: targetPlan.id, name: targetPlan.name, price: targetPlan.price },
                expiredAt: new Date(activePending.expiresAt).toISOString(),
                isReused: true
              });
            } else {
              activePending.status = "cancelled";
              activePending.cancelledAt = nowCheck;
              ctx.waitUntil(cancelPakasirTransaction(activePending.orderId, activePending.amount));
              ctx.waitUntil(safeKvPut(kv, `order:${activePending.orderId}`, JSON.stringify(activePending), { expirationTtl: 86400 }));
            }
          }
        } catch (_) {}

        const plan = PLANS[planId] || PLANS["1_month"];
        const orderId = `GN-${plan.id.toUpperCase()}-${Date.now().toString().slice(-6)}-${Math.random().toString(36).substring(2, 6).toUpperCase()}`;

        const mode = (await safeKvGet(kv, "config:mode")) || "production";
        const isSandbox = mode === "sandbox";

        const calculatedFee = calculatePakasirFee(plan.price, paymentMethod, bank);
        const pakasirMethod = getPakasirMethod(paymentMethod, bank);
        let pakasirResult = null;

        if (env.PAKASIR_PROJECT && env.PAKASIR_API_KEY) {
          try {
            const resp = await fetch(`https://app.pakasir.com/api/transactioncreate/${pakasirMethod}`, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({
                project: env.PAKASIR_PROJECT,
                order_id: orderId,
                amount: plan.price,
                api_key: env.PAKASIR_API_KEY
              })
            });
            if (resp.ok) {
              pakasirResult = await resp.json();
            }
          } catch (_) {}
        }

        const resData = pakasirResult ? (pakasirResult.payment || pakasirResult.transaction || pakasirResult) : null;

        const fee = (resData && resData.fee != null) ? Number(resData.fee) : calculatedFee;
        const totalPayment = (resData && resData.total_payment != null) ? Number(resData.total_payment) : (plan.price + fee);
        
        let qrisString = null;
        let vaNumber = null;
        let vaBank = bank.toUpperCase();

        if (paymentMethod === "qris") {
          qrisString = (resData && (resData.payment_number || resData.qris_string || resData.qr_string)) ||
            `00020101021226500014ID.LINKAJA.WWW011893600911002${orderId}520458125303360540${totalPayment}5802ID5910GAME NUKE6007JAKARTA6304`;
        } else {
          if (resData && (resData.payment_number || resData.va_number || resData.virtual_account)) {
            vaNumber = String(resData.payment_number || resData.va_number || resData.virtual_account);
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
          deviceId,
          planId: plan.id,
          amount: plan.price,
          fee,
          totalPayment,
          paymentMethod,
          vaNumber,
          vaBank,
          status: "pending",
          isSandbox,
          createdAt: now,
          expiresAt
        };

        setMemOrder(orderId, orderRecord);
        ctx.waitUntil(safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 86400 }));

        try {
          let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
          devOrders.unshift({
            orderId,
            planId: plan.id,
            planName: plan.name,
            amount: plan.price,
            fee,
            totalPayment,
            paymentMethod,
            qrisString,
            vaNumber,
            vaBank,
            status: "pending",
            createdAt: now,
            expiresAt
          });
          if (devOrders.length > 20) devOrders = devOrders.slice(0, 20);
          ctx.waitUntil(safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 7 * 86400 }));
        } catch (_) {}

        try {
          let allOrders = await getIndexedOrders();
          allOrders.unshift(orderRecord);
          ctx.waitUntil(saveIndexedOrders(allOrders));
        } catch (_) {}

        return jsonResponse({
          success: true,
          orderId,
          amount: plan.price,
          fee,
          totalPayment,
          paymentMethod,
          qrisString,
          vaNumber,
          vaBank,
          plan: { id: plan.id, name: plan.name, price: plan.price },
          expiredAt: new Date(expiresAt).toISOString()
        });
      }

      if (path === "/api/orders/device") {
        const deviceId = (url.searchParams.get("deviceId") || url.searchParams.get("device_id") || "").trim();
        if (!deviceId) return jsonResponse({ error: "deviceId required" }, 400);

        let devOrders = (await safeKvGet(kv, `device_orders:${deviceId}`, "json")) || [];
        const now = Date.now();
        let changed = false;
        for (const o of devOrders) {
          const isExp = o.status === "pending" && (o.expiresAt ? (now > o.expiresAt) : ((now - (o.createdAt || 0)) > 15 * 60000));
          if (isExp) {
            o.status = "expired";
            changed = true;
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
          ctx.waitUntil(safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 7 * 86400 }));
        }
        return jsonResponse({ success: true, orders: devOrders }, 200, 10);
      }

      if (path === "/api/order/status") {
        const orderId = (url.searchParams.get("orderId") || url.searchParams.get("order_id") || "").trim();
        if (!orderId) return jsonResponse({ error: "orderId required" }, 400);

        let orderRecord = getMemOrder(orderId);
        if (!orderRecord) {
          const orderRecordStr = await safeKvGet(kv, `order:${orderId}`);
          if (orderRecordStr) {
            try { orderRecord = JSON.parse(orderRecordStr); } catch (_) {}
          }
        }

        if (!orderRecord) return jsonResponse({ success: false, status: "not_found" }, 404);
        const now = Date.now();

        if (orderRecord.status === "pending" && env.PAKASIR_PROJECT && env.PAKASIR_API_KEY) {
          try {
            const detailUrl = `https://app.pakasir.com/api/transactiondetail?project=${encodeURIComponent(env.PAKASIR_PROJECT)}&amount=${orderRecord.amount}&order_id=${encodeURIComponent(orderId)}&api_key=${encodeURIComponent(env.PAKASIR_API_KEY)}`;
            const dResp = await fetch(detailUrl);
            if (dResp.ok) {
              const dJson = await dResp.json();
              const dData = dJson.transaction || dJson.payment || dJson;
              if (dData && dData.status === "completed") {
                orderRecord.status = "completed";
                orderRecord.paidAt = now;
                setMemOrder(orderId, orderRecord);
                ctx.waitUntil(safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 86400 }));

                const plan = PLANS[orderRecord.planId] || PLANS["1_month"];
                let existingDev = getMemDevice(orderRecord.deviceId) || (await safeKvGet(kv, `device:${orderRecord.deviceId}`, "json")) || {};
                const currentExpires = existingDev.expiresAt && existingDev.expiresAt > now ? existingDev.expiresAt : now;
                const newExpiresAt = currentExpires + plan.durationMs;

                const devData = {
                  deviceId: orderRecord.deviceId,
                  planId: plan.id,
                  planName: plan.name,
                  isTrial: false,
                  trialUsed: true,
                  expiresAt: newExpiresAt,
                  updatedAt: now,
                  source: "pakasir_status_sync"
                };

                setMemDevice(orderRecord.deviceId, devData);
                ctx.waitUntil(safeKvPut(kv, `device:${orderRecord.deviceId}`, JSON.stringify(devData)));

                ctx.waitUntil((async () => {
                  try {
                    let devs = await getIndexedDevices();
                    const idx = devs.findIndex(d => d.deviceId === orderRecord.deviceId);
                    if (idx >= 0) devs[idx] = devData; else devs.unshift(devData);
                    await saveIndexedDevices(devs);
                  } catch (_) {}
                })());

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
                  for (const o of devOrders) {
                    if (o.orderId === orderId) o.status = "completed";
                  }
                  ctx.waitUntil(safeKvPut(kv, `device_orders:${orderRecord.deviceId}`, JSON.stringify(devOrders), { expirationTtl: 7 * 86400 }));
                } catch (_) {}
              }
            }
          } catch (_) {}
        }

        const isExp = orderRecord.status === "pending" && (orderRecord.expiresAt ? (now > orderRecord.expiresAt) : ((now - (orderRecord.createdAt || 0)) > 15 * 60000));
        if (isExp) {
          orderRecord.status = "expired";
          setMemOrder(orderId, orderRecord);
          ctx.waitUntil(safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 86400 }));
          ctx.waitUntil(cancelPakasirTransaction(orderId, orderRecord.amount));

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
                ctx.waitUntil(safeKvPut(kv, `device_orders:${orderRecord.deviceId}`, JSON.stringify(devOrders), { expirationTtl: 7 * 86400 }));
              }
            } catch (_) {}
          }
        }

        return jsonResponse({
          success: true,
          status: orderRecord.status,
          orderId: orderRecord.orderId,
          planId: orderRecord.planId,
          totalPayment: orderRecord.totalPayment,
          paymentMethod: orderRecord.paymentMethod,
          createdAt: orderRecord.createdAt,
          expiresAt: orderRecord.expiresAt
        }, 200, 5);
      }

      if (path === "/api/order/cancel" && request.method === "POST") {
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
          ctx.waitUntil(cancelPakasirTransaction(orderId, orderRecord.amount));
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
            ctx.waitUntil(safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 7 * 86400 }));
          } catch (_) {}
        }

        return jsonResponse({ success: true, message: "Order cancelled" });
      }

      if ((path === "/webhook" || path === "/api/webhook" || path === "/api/webhook/pakasir") && request.method === "POST") {
        const body = await safeJson(request);
        const orderId = (body.order_id || body.orderId || "").trim();

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

        if (!env.PAKASIR_PROJECT || !env.PAKASIR_API_KEY) {
          return jsonResponse({ error: "Gateway credentials not configured on server" }, 500);
        }

        let verifyData = null;
        try {
          const verifyUrl = `https://app.pakasir.com/api/transactiondetail?project=${encodeURIComponent(env.PAKASIR_PROJECT)}&amount=${orderRecord.amount}&order_id=${encodeURIComponent(orderId)}&api_key=${encodeURIComponent(env.PAKASIR_API_KEY)}`;
          const verifyResp = await fetch(verifyUrl);
          if (verifyResp.ok) {
            verifyData = await verifyResp.json();
          }
        } catch (err) {
          return jsonResponse({ error: "Failed to connect to Pakasir verification API", details: err.message }, 502);
        }

        const txData = verifyData ? (verifyData.transaction || verifyData.payment || verifyData) : null;

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
        orderRecord.verifiedVia = "pakasir_double_check";
        setMemOrder(orderId, orderRecord);
        ctx.waitUntil(safeKvPut(kv, `order:${orderId}`, JSON.stringify(orderRecord), { expirationTtl: 86400 }));

        const plan = PLANS[orderRecord.planId] || PLANS["1_month"];
        const now = Date.now();
        let existingDev = getMemDevice(orderRecord.deviceId) || (await safeKvGet(kv, `device:${orderRecord.deviceId}`, "json")) || {};
        const currentExpires = existingDev.expiresAt && existingDev.expiresAt > now ? existingDev.expiresAt : now;
        const newExpiresAt = currentExpires + plan.durationMs;

        const devData = {
          deviceId: orderRecord.deviceId,
          planId: plan.id,
          planName: plan.name,
          isTrial: false,
          trialUsed: true,
          expiresAt: newExpiresAt,
          updatedAt: now,
          source: "pakasir_verified_webhook"
        };

        setMemDevice(orderRecord.deviceId, devData);
        ctx.waitUntil(safeKvPut(kv, `device:${orderRecord.deviceId}`, JSON.stringify(devData)));

        ctx.waitUntil((async () => {
          try {
            let devs = await getIndexedDevices();
            const idx = devs.findIndex(d => d.deviceId === orderRecord.deviceId);
            if (idx >= 0) devs[idx] = devData; else devs.unshift(devData);
            await saveIndexedDevices(devs);
          } catch (_) {}
        })());

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
          for (const o of devOrders) {
            if (o.orderId === orderId) o.status = "completed";
          }
          ctx.waitUntil(safeKvPut(kv, `device_orders:${orderRecord.deviceId}`, JSON.stringify(devOrders), { expirationTtl: 7 * 86400 }));
        } catch (_) {}

        return jsonResponse({ success: true, message: "Subscription activated" });
      }

      if (path === "/api/admin/login" && request.method === "POST") {
        const body = await safeJson(request);
        const user = (body.username || "").trim();
        const pass = (body.password || "").trim();

        if (user === ADMIN_USER && pass === ADMIN_PASS) {
          const token = btoa(`${ADMIN_USER}:${ADMIN_SECRET}:${Date.now()}`);
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
            if (parts[0] === ADMIN_USER && parts[1] === ADMIN_SECRET) {
              const tokenTs = Number(parts[2]) || 0;
              if (tokenTs > 0 && (Date.now() - tokenTs) < 7 * 86400 * 1000) {
                authorized = true;
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
          let activeTrials = 0;
          for (const d of devices) {
            if (d && d.expiresAt > now) {
              if (d.isTrial) activeTrials++;
              else activeVip++;
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
            activeTrials,
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
            const devices = await getIndexedDevices();
            return jsonResponse({ success: true, devices });
          }

          if (request.method === "POST") {
            const body = await safeJson(request);
            const deviceId = (body.deviceId || "").trim();
            const days = Number(body.days || 30);
            const planId = body.planId || "VIP_ADMIN";

            if (!deviceId) return jsonResponse({ error: "deviceId required" }, 400);

            const now = Date.now();
            const existing = (await safeKvGet(kv, `device:${deviceId}`, "json")) || {};
            const currentExp = existing.expiresAt && existing.expiresAt > now ? existing.expiresAt : now;
            const newExpiresAt = currentExp + (days * 86400000);

            const devData = {
              deviceId,
              planId,
              isTrial: false,
              trialUsed: true,
              expiresAt: newExpiresAt,
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
                planName: days >= 999 ? "Lifetime VIP Pass" : `${days} Days VIP Pass`,
                amount: 0,
                fee: 0,
                totalPayment: 0,
                paymentMethod: "admin_grant",
                status: "completed",
                createdAt: now,
                expiresAt: newExpiresAt
              });
              if (devOrders.length > 20) devOrders = devOrders.slice(0, 20);
              await safeKvPut(kv, `device_orders:${deviceId}`, JSON.stringify(devOrders), { expirationTtl: 7 * 86400 });
            } catch (_) {}

            return jsonResponse({ success: true, device: devData });
          }

          if (request.method === "DELETE") {
            const deviceId = (url.searchParams.get("deviceId") || "").trim();
            if (!deviceId) return jsonResponse({ error: "deviceId required" }, 400);

            await safeKvDelete(kv, `device:${deviceId}`);
            delMemDevice(deviceId);

            let devs = await getIndexedDevices();
            devs = devs.filter(d => d.deviceId !== deviceId);
            await saveIndexedDevices(devs);

            return jsonResponse({ success: true, message: `Device ${deviceId} revoked` });
          }
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
                  await safeKvPut(kv, `device_orders:${orderRecord.deviceId}`, JSON.stringify(devOrders), { expirationTtl: 7 * 86400 });
                }
              } catch (_) {}
            }

            if (newStatus === "completed") {
              const plan = PLANS[orderRecord.planId] || PLANS["1_month"];
              const now = Date.now();
              const existingDev = (await safeKvGet(kv, `device:${orderRecord.deviceId}`, "json")) || {};
              const curExp = existingDev.expiresAt && existingDev.expiresAt > now ? existingDev.expiresAt : now;
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

          if (!env.PAKASIR_PROJECT || !env.PAKASIR_API_KEY) {
            return jsonResponse({ error: "Pakasir credentials not configured" }, 500);
          }

          const verifyUrl = `https://app.pakasir.com/api/transactiondetail?project=${encodeURIComponent(env.PAKASIR_PROJECT)}&amount=${order.amount}&order_id=${encodeURIComponent(orderId)}&api_key=${encodeURIComponent(env.PAKASIR_API_KEY)}`;
          const vResp = await fetch(verifyUrl);
          const vJson = await vResp.json();
          return jsonResponse({ success: true, pakasirData: vJson, localOrder: order });
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
                pakasirConfigured: Boolean(env.PAKASIR_PROJECT && env.PAKASIR_API_KEY)
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
