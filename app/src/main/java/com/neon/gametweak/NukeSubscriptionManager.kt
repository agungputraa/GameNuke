package com.neon.gametweak

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NukeSubscriptionManager {

    private const val PREFS_NAME = "nuke_subscription_prefs"
    private const val KEY_DEVICE_ID = "sub_device_id"
    private const val KEY_EXPIRES_AT = "sub_expires_at"
    private const val KEY_PLAN_ID = "sub_plan_id"
    private const val KEY_LAST_SYNC_TS = "sub_last_sync_ts"
    private const val SYNC_THROTTLE_MS = 15 * 1000L // 15 seconds throttle for reactive sync

    private const val KEY_ORDER_HISTORY = "sub_order_history"
    private const val BASE_URL = "https://gamenukevip.agungofficialdev.workers.dev"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _subscriptionState = MutableStateFlow(
        SubscriptionStatus(isActive = false, planId = null, expiresAt = 0L, remainingDays = 0, remainingSeconds = 0L)
    )
    val subscriptionState: StateFlow<SubscriptionStatus> = _subscriptionState.asStateFlow()

    data class Plan(
        val id: String,
        val name: String,
        val durationDays: Int,
        val price: Long,
        val badge: String
    )

    data class SubscriptionStatus(
        val isActive: Boolean,
        val planId: String?,
        val expiresAt: Long,
        val remainingDays: Int,
        val remainingSeconds: Long
    )

    data class OrderResult(
        val success: Boolean,
        val orderId: String?,
        val amount: Long,
        val fee: Long,
        val totalPayment: Long,
        val paymentMethod: String = "qris",
        val qrisString: String?,
        val vaNumber: String? = null,
        val vaBank: String? = null,
        val expiredAt: String?,
        val errorMessage: String? = null
    )

    data class OrderRecord(
        val orderId: String,
        val planId: String,
        val planName: String,
        val amount: Long,
        val fee: Long,
        val totalPayment: Long,
        val paymentMethod: String = "qris",
        val qrisString: String?,
        val vaNumber: String? = null,
        val vaBank: String? = null,
        val status: String, // "PENDING", "COMPLETED", "EXPIRED", "CANCELLED"
        val createdAt: Long,
        val expiresAt: Long
    )

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val sp = prefs(context)
        val existing = sp.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        var hardwareId: String? = null
        try {
            hardwareId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {}

        val finalId = if (!hardwareId.isNullOrBlank() && hardwareId != "9774d56d682e549c") {
            hardwareId
        } else {
            UUID.randomUUID().toString().replace("-", "")
        }

        sp.edit().putString(KEY_DEVICE_ID, finalId).apply()
        return finalId
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        _subscriptionState.value = getLocalStatus(appContext)
        syncStatusIfNeeded(appContext, force = true)
    }

    fun isVipActive(context: Context): Boolean {
        val expiresAt = prefs(context).getLong(KEY_EXPIRES_AT, 0L)
        val active = System.currentTimeMillis() < expiresAt
        if (_subscriptionState.value.isActive != active) {
            _subscriptionState.value = getLocalStatus(context)
        }
        return active
    }

    fun getRemainingDays(context: Context): Int {
        val expiresAt = prefs(context).getLong(KEY_EXPIRES_AT, 0L)
        val diff = expiresAt - System.currentTimeMillis()
        return if (diff > 0) {
            Math.ceil(diff.toDouble() / (24.0 * 60.0 * 60.0 * 1000.0)).toInt()
        } else {
            0
        }
    }

    fun getActivePlanId(context: Context): String? {
        return if (isVipActive(context)) {
            prefs(context).getString(KEY_PLAN_ID, null)
        } else {
            null
        }
    }

    fun getLocalStatus(context: Context): SubscriptionStatus {
        val expiresAt = prefs(context).getLong(KEY_EXPIRES_AT, 0L)
        val now = System.currentTimeMillis()
        val diff = expiresAt - now
        val active = diff > 0
        val remainingDays = if (active) Math.ceil(diff.toDouble() / (24.0 * 60.0 * 60.0 * 1000.0)).toInt() else 0
        val remainingSeconds = if (active) (diff / 1000L) else 0L
        val planId = prefs(context).getString(KEY_PLAN_ID, null)

        return SubscriptionStatus(
            isActive = active,
            planId = planId,
            expiresAt = expiresAt,
            remainingDays = remainingDays,
            remainingSeconds = remainingSeconds
        )
    }

    fun syncStatusIfNeeded(context: Context, force: Boolean = false, callback: ((SubscriptionStatus) -> Unit)? = null) {
        val appContext = context.applicationContext
        val sp = prefs(appContext)
        val lastSync = sp.getLong(KEY_LAST_SYNC_TS, 0L)
        val now = System.currentTimeMillis()

        if (!force && (now - lastSync) < SYNC_THROTTLE_MS) {
            val local = getLocalStatus(appContext)
            _subscriptionState.value = local
            callback?.invoke(local)
            return
        }

        val deviceId = getDeviceId(appContext)
        executor.execute {
            var status = getLocalStatus(appContext)
            try {
                val endpoint = "$BASE_URL/api/subscription/status?deviceId=$deviceId"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "GameNuke-App/3.2")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val responseStr = reader.use { it.readText() }
                    val json = JSONObject(responseStr)

                    val expiresAt = json.optLong("expiresAt", 0L)
                    val planId = json.optString("planId", "")

                    sp.edit()
                        .putLong(KEY_EXPIRES_AT, expiresAt)
                        .putString(KEY_PLAN_ID, planId)
                        .putLong(KEY_LAST_SYNC_TS, System.currentTimeMillis())
                        .apply()

                    status = getLocalStatus(appContext)
                }
                conn.disconnect()
            } catch (_: Exception) {}

            mainHandler.post {
                _subscriptionState.value = status
                callback?.invoke(status)
            }
        }
    }

    fun fetchPlansAsync(callback: (List<Plan>) -> Unit) {
        executor.execute {
            val planList = mutableListOf<Plan>()
            try {
                val endpoint = "$BASE_URL/api/plans"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "GameNuke-App/3.2")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val responseStr = reader.use { it.readText() }
                    val json = JSONObject(responseStr)
                    val array = json.optJSONArray("plans") ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        planList.add(
                            Plan(
                                id = item.getString("id"),
                                name = item.getString("name"),
                                durationDays = item.getInt("durationDays"),
                                price = item.getLong("price"),
                                badge = item.optString("badge", "")
                            )
                        )
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}

            if (planList.isEmpty()) {
                planList.add(Plan("1_week", "Weekly VIP Pass", 7, 15000L, "Flexible Trial"))
                planList.add(Plan("1_month", "Monthly VIP Pass", 30, 35000L, "Most Popular"))
                planList.add(Plan("6_months", "Semi-Annual VIP Pass", 180, 160000L, "Save 25%"))
                planList.add(Plan("1_year", "Annual VIP Pass", 365, 260000L, "Best Value"))
            }

            mainHandler.post {
                callback(planList)
            }
        }
    }

    fun createOrderAsync(
        context: Context,
        planId: String,
        paymentMethod: String = "qris",
        bank: String = "bca",
        callback: (OrderResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val deviceId = getDeviceId(appContext)

        executor.execute {
            var orderResult: OrderResult
            try {
                val endpoint = "$BASE_URL/api/order/create"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "GameNuke-App/3.2")

                val payload = JSONObject()
                payload.put("deviceId", deviceId)
                payload.put("planId", planId)
                payload.put("paymentMethod", paymentMethod)
                payload.put("bank", bank)

                OutputStreamWriter(conn.outputStream).use {
                    it.write(payload.toString())
                    it.flush()
                }

                if (conn.responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val responseStr = reader.use { it.readText() }
                    val json = JSONObject(responseStr)

                    val orderId = json.optString("orderId")
                    val planObj = json.optJSONObject("plan")
                    val amount = planObj?.optLong("price") ?: json.optLong("amount", 0L)
                    val fee = json.optLong("fee", 0L)
                    val totalPayment = json.optLong("totalPayment", amount + fee)
                    val method = json.optString("paymentMethod", paymentMethod)
                    val qrisString = json.optString("qrisString").takeIf { !it.isNullOrBlank() }
                    val vaNumber = json.optString("vaNumber").takeIf { !it.isNullOrBlank() }
                    val vaBank = json.optString("vaBank", bank.uppercase()).takeIf { !it.isNullOrBlank() }
                    val expiredAt = json.optString("expiredAt").takeIf { !it.isNullOrBlank() }

                    orderResult = OrderResult(
                        success = true,
                        orderId = orderId,
                        amount = amount,
                        fee = fee,
                        totalPayment = totalPayment,
                        paymentMethod = method,
                        qrisString = qrisString,
                        vaNumber = vaNumber,
                        vaBank = vaBank,
                        expiredAt = expiredAt
                    )

                    val planName = when (planId) {
                        "1_week" -> "Weekly VIP Pass"
                        "1_month" -> "Monthly VIP Pass"
                        "6_months" -> "Semi-Annual VIP Pass"
                        "1_year" -> "Annual VIP Pass"
                        else -> "VIP Pass"
                    }
                    val record = OrderRecord(
                        orderId = orderId,
                        planId = planId,
                        planName = planName,
                        amount = amount,
                        fee = fee,
                        totalPayment = totalPayment,
                        paymentMethod = method,
                        qrisString = qrisString,
                        vaNumber = vaNumber,
                        vaBank = vaBank,
                        status = "PENDING",
                        createdAt = System.currentTimeMillis(),
                        expiresAt = System.currentTimeMillis() + 15 * 60 * 1000L
                    )
                    saveOrderToHistory(appContext, record)
                } else {
                    val errReader = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream))
                    val errStr = errReader.use { it.readText() }
                    orderResult = OrderResult(
                        success = false,
                        orderId = null,
                        amount = 0L,
                        fee = 0L,
                        totalPayment = 0L,
                        paymentMethod = paymentMethod,
                        qrisString = null,
                        vaNumber = null,
                        vaBank = null,
                        expiredAt = null,
                        errorMessage = errStr
                    )
                }
                conn.disconnect()
            } catch (e: Exception) {
                orderResult = OrderResult(
                    success = false,
                    orderId = null,
                    amount = 0L,
                    fee = 0L,
                    totalPayment = 0L,
                    paymentMethod = paymentMethod,
                    qrisString = null,
                    vaNumber = null,
                    vaBank = null,
                    expiredAt = null,
                    errorMessage = e.message ?: "Connection error"
                )
            }

            mainHandler.post {
                callback(orderResult)
            }
        }
    }

    fun getOrderHistory(context: Context): List<OrderRecord> {
        val sp = prefs(context)
        val raw = sp.getString(KEY_ORDER_HISTORY, null) ?: return emptyList()
        val list = mutableListOf<OrderRecord>()
        var modified = false
        val now = System.currentTimeMillis()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                var status = obj.optString("status", "PENDING").uppercase()
                val expiresAt = obj.optLong("expiresAt", 0L)
                if (status == "PENDING" && expiresAt > 0 && now > expiresAt) {
                    status = "EXPIRED"
                    obj.put("status", "EXPIRED")
                    modified = true
                }
                list.add(
                    OrderRecord(
                        orderId = obj.getString("orderId"),
                        planId = obj.optString("planId", ""),
                        planName = obj.optString("planName", "VIP Pass"),
                        amount = obj.optLong("amount", 0L),
                        fee = obj.optLong("fee", 0L),
                        totalPayment = obj.optLong("totalPayment", 0L),
                        paymentMethod = obj.optString("paymentMethod", "qris"),
                        qrisString = obj.optString("qrisString").takeIf { !it.isNullOrBlank() },
                        vaNumber = obj.optString("vaNumber").takeIf { !it.isNullOrBlank() },
                        vaBank = obj.optString("vaBank").takeIf { !it.isNullOrBlank() },
                        status = status,
                        createdAt = obj.optLong("createdAt", 0L),
                        expiresAt = expiresAt
                    )
                )
            }
            if (modified) {
                sp.edit().putString(KEY_ORDER_HISTORY, array.toString()).apply()
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.createdAt }
    }

    fun saveOrderToHistory(context: Context, record: OrderRecord) {
        val sp = prefs(context)
        val existing = getOrderHistory(context).toMutableList()
        val index = existing.indexOfFirst { it.orderId == record.orderId }
        if (index >= 0) {
            existing[index] = record
        } else {
            existing.add(0, record)
        }
        val trimmed = existing.take(30)
        val array = JSONArray()
        for (item in trimmed) {
            val obj = JSONObject()
            obj.put("orderId", item.orderId)
            obj.put("planId", item.planId)
            obj.put("planName", item.planName)
            obj.put("amount", item.amount)
            obj.put("fee", item.fee)
            obj.put("totalPayment", item.totalPayment)
            obj.put("paymentMethod", item.paymentMethod)
            obj.put("qrisString", item.qrisString ?: "")
            obj.put("vaNumber", item.vaNumber ?: "")
            obj.put("vaBank", item.vaBank ?: "")
            obj.put("status", item.status)
            obj.put("createdAt", item.createdAt)
            obj.put("expiresAt", item.expiresAt)
            array.put(obj)
        }
        sp.edit().putString(KEY_ORDER_HISTORY, array.toString()).apply()
    }

    fun updateOrderStatus(context: Context, orderId: String, newStatus: String) {
        val sp = prefs(context)
        val raw = sp.getString(KEY_ORDER_HISTORY, null) ?: return
        try {
            val array = JSONArray(raw)
            var changed = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("orderId") == orderId) {
                    obj.put("status", newStatus.uppercase())
                    changed = true
                    break
                }
            }
            if (changed) {
                sp.edit().putString(KEY_ORDER_HISTORY, array.toString()).apply()
            }
        } catch (_: Exception) {}
    }

    fun cancelOrderAsync(context: Context, orderId: String, callback: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        val deviceId = getDeviceId(appContext)
        executor.execute {
            var ok = false
            try {
                val endpoint = "$BASE_URL/api/order/cancel"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")

                val payload = JSONObject()
                payload.put("orderId", orderId)
                payload.put("deviceId", deviceId)

                OutputStreamWriter(conn.outputStream).use {
                    it.write(payload.toString())
                    it.flush()
                }

                if (conn.responseCode in 200..299) {
                    ok = true
                }
                conn.disconnect()
            } catch (_: Exception) {}

            updateOrderStatus(appContext, orderId, "CANCELLED")
            mainHandler.post { callback(ok) }
        }
    }

    fun checkOrderStatusAsync(context: Context, orderId: String, callback: (String) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            var status = "PENDING"
            try {
                val endpoint = "$BASE_URL/api/order/status?orderId=$orderId"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.setRequestProperty("Accept", "application/json")

                if (conn.responseCode in 200..299) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(resp)
                    status = json.optString("status", "pending").uppercase()
                    updateOrderStatus(appContext, orderId, status)
                    if (status == "COMPLETED" || status == "PAID") {
                        syncStatusIfNeeded(appContext, force = true)
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}

            mainHandler.post { callback(status) }
        }
    }

    fun syncDeviceOrdersAsync(context: Context, callback: ((List<OrderRecord>) -> Unit)? = null) {
        val appContext = context.applicationContext
        val deviceId = getDeviceId(appContext)

        executor.execute {
            try {
                val endpoint = "$BASE_URL/api/orders/device?deviceId=$deviceId"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.setRequestProperty("Accept", "application/json")

                if (conn.responseCode in 200..299) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(resp)
                    if (json.optBoolean("success", false)) {
                        val arr = json.optJSONArray("orders") ?: JSONArray()
                        var hasCompleted = false
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val oid = item.optString("orderId", "")
                            val st = item.optString("status", "pending").uppercase()
                            if (oid.isNotBlank()) {
                                updateOrderStatus(appContext, oid, st)
                                if (st == "COMPLETED" || st == "PAID") {
                                    hasCompleted = true
                                }
                            }
                        }
                        if (hasCompleted) {
                            syncStatusIfNeeded(appContext, force = true)
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}

            val updatedList = getOrderHistory(appContext)
            mainHandler.post {
                callback?.invoke(updatedList)
            }
        }
    }
}
