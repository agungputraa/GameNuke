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
    private const val KEY_CREDIT_BALANCE = "sub_credit_balance"
    private const val KEY_LAST_SYNC_TS = "sub_last_sync_ts"
    private const val SYNC_THROTTLE_MS = 15 * 1000L // 15 seconds throttle for reactive sync

    private const val KEY_ORDER_HISTORY = "sub_order_history"
    private const val KEY_SIGNATURE_SEAL = "sub_sig_seal"
    private const val ANTI_TUYUL_SALT = "Spectra_Nuke_AntiTuyul_#9981_Salt"
    private const val BASE_URL = "https://gamenukevip.agungofficialdev.workers.dev"

    private fun sha256Hex(raw: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            (raw.hashCode() xor 0x5C).toString(16)
        }
    }

    private fun generateLegacySeal(deviceId: String, expiresAt: Long): String {
        val raw = "$deviceId:$expiresAt:$ANTI_TUYUL_SALT"
        return sha256Hex(raw)
    }

    private fun generateSeal(deviceId: String, expiresAt: Long, creditBalance: Int): String {
        val raw = "$deviceId:$expiresAt:$creditBalance:$ANTI_TUYUL_SALT"
        return sha256Hex(raw)
    }

    private fun verifySeal(deviceId: String, expiresAt: Long, creditBalance: Int, storedSeal: String?): Boolean {
        if (storedSeal.isNullOrBlank()) return false
        val expected = generateSeal(deviceId, expiresAt, creditBalance)
        if (expected.equals(storedSeal, ignoreCase = true)) return true
        // Legacy fallback for seamless migration of existing active subscribers
        val legacy = generateLegacySeal(deviceId, expiresAt)
        return legacy.equals(storedSeal, ignoreCase = true)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _subscriptionState = MutableStateFlow(
        SubscriptionStatus(isActive = false, planId = null, expiresAt = 0L, remainingDays = 0, remainingSeconds = 0L, creditBalance = 0)
    )
    val subscriptionState: StateFlow<SubscriptionStatus> = _subscriptionState.asStateFlow()

    data class Plan(
        val id: String,
        val name: String,
        val durationDays: Int,
        val price: Long,
        val badge: String
    )

    data class CreditPack(
        val id: String,
        val name: String,
        val credits: Int,
        val price: Long,
        val badge: String,
        val description: String
    )

    data class PlansAndCreditsResult(
        val plans: List<Plan>,
        val creditPacks: List<CreditPack>
    )

    data class SubscriptionStatus(
        val isActive: Boolean,
        val planId: String?,
        val expiresAt: Long,
        val remainingDays: Int,
        val remainingSeconds: Long,
        val creditBalance: Int = 0
    )

    data class ConsumeResult(
        val success: Boolean,
        val newBalance: Int,
        val expiresAt: Long = 0L,
        val sessionPassGranted: Boolean = false,
        val errorMessage: String? = null
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
        val paymentLink: String? = null,
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
        val paymentLink: String? = null,
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
        val existing = sp.getString(KEY_DEVICE_ID, null)?.trim()?.lowercase()
        if (!existing.isNullOrBlank()) {
            return existing
        }

        var hardwareId: String? = null
        try {
            hardwareId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim()?.lowercase()
        } catch (_: Exception) {}

        val finalId = if (!hardwareId.isNullOrBlank() && hardwareId != "9774d56d682e549c") {
            hardwareId
        } else {
            UUID.randomUUID().toString().replace("-", "").lowercase()
        }

        sp.edit().putString(KEY_DEVICE_ID, finalId).apply()
        return finalId
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        _subscriptionState.value = getLocalStatus(appContext)
        syncStatusIfNeeded(appContext, force = true)
        syncDeviceOrdersAsync(appContext)
    }

    fun isVipActive(context: Context): Boolean {
        // 1. Anti-Tamper & Repack Verification
        if (IntegrityGuard.isCompromised()) return false

        val sp = prefs(context)
        val expiresAt = sp.getLong(KEY_EXPIRES_AT, 0L)

        // Kredit adalah MATA UANG untuk membeli sesi waktu.
        // VIP aktif HANYA jika ada sesi waktu yang valid (expiresAt > sekarang).
        // Kepemilikan kredit BUKAN berarti VIP aktif — user harus redeem dulu.
        val timeActive = System.currentTimeMillis() < expiresAt
        if (!timeActive) return false

        // 2. Cryptographic Anti-Tuyul Seal Verification
        val creditBalance = sp.getInt(KEY_CREDIT_BALANCE, 0)
        val deviceId = getDeviceId(context)
        val storedSeal = sp.getString(KEY_SIGNATURE_SEAL, null)
        if (!verifySeal(deviceId, expiresAt, creditBalance, storedSeal)) {
            sp.edit().putLong(KEY_EXPIRES_AT, 0L).remove(KEY_SIGNATURE_SEAL).apply()
            return false
        }

        if (!_subscriptionState.value.isActive) {
            _subscriptionState.value = getLocalStatus(context)
        }
        return true
    }

    fun getCreditBalance(context: Context): Int {
        if (IntegrityGuard.isCompromised()) return 0
        val sp = prefs(context)
        val expiresAt = sp.getLong(KEY_EXPIRES_AT, 0L)
        val creditBalance = sp.getInt(KEY_CREDIT_BALANCE, 0)
        val deviceId = getDeviceId(context)
        val storedSeal = sp.getString(KEY_SIGNATURE_SEAL, null)
        if (storedSeal.isNullOrBlank() || !verifySeal(deviceId, expiresAt, creditBalance, storedSeal)) {
            return 0
        }
        return creditBalance.coerceAtLeast(0)
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
        val sp = prefs(context)
        val expiresAt = sp.getLong(KEY_EXPIRES_AT, 0L)
        val creditBalance = sp.getInt(KEY_CREDIT_BALANCE, 0)
        val now = System.currentTimeMillis()
        val diff = expiresAt - now
        // isActive = HANYA berdasarkan waktu. Kredit adalah saldo, bukan status aktif.
        val timeActive = diff > 0
        val remainingDays = if (timeActive) Math.ceil(diff.toDouble() / (24.0 * 60.0 * 60.0 * 1000.0)).toInt() else 0
        val remainingSeconds = if (timeActive) (diff / 1000L) else 0L
        val planId = sp.getString(KEY_PLAN_ID, null)

        return SubscriptionStatus(
            isActive = timeActive,
            planId = planId,
            expiresAt = expiresAt,
            remainingDays = remainingDays,
            remainingSeconds = remainingSeconds,
            creditBalance = creditBalance
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
        val recentOrder = getOrderHistory(appContext).firstOrNull { it.status == "COMPLETED" }?.orderId
        val query = if (!recentOrder.isNullOrBlank()) {
            "deviceId=$deviceId&orderId=$recentOrder&_t=${System.currentTimeMillis()}"
        } else {
            "deviceId=$deviceId&_t=${System.currentTimeMillis()}"
        }

        executor.execute {
            var status = getLocalStatus(appContext)
            try {
                val endpoint = "$BASE_URL/api/subscription/status?$query"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "GameNuke-App/3.6.0-Apeiron")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val responseStr = reader.use { it.readText() }
                    val json = JSONObject(responseStr)

                    val expiresAt = if (json.has("expiresAt")) json.optLong("expiresAt", 0L) else sp.getLong(KEY_EXPIRES_AT, 0L)
                    val planId = json.optString("planId", sp.getString(KEY_PLAN_ID, "") ?: "")

                    // SERVER ADALAH SUMBER KEBENARAN UNTUK KREDIT.
                    // Tidak ada override lokal. Jika server bilang 0, maka 0.
                    // Ini mencegah bug kredit tidak berkurang.
                    val serverCredits = if (json.has("creditBalance")) json.optInt("creditBalance", 0) else sp.getInt(KEY_CREDIT_BALANCE, 0)
                    val finalCreditBalance = serverCredits.coerceAtLeast(0)

                    val seal = generateSeal(deviceId, expiresAt, finalCreditBalance)
                    sp.edit()
                        .putLong(KEY_EXPIRES_AT, expiresAt)
                        .putString(KEY_PLAN_ID, planId)
                        .putInt(KEY_CREDIT_BALANCE, finalCreditBalance)
                        .putString(KEY_SIGNATURE_SEAL, seal)
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

    fun fetchPlansAndCreditsAsync(callback: (PlansAndCreditsResult) -> Unit) {
        executor.execute {
            val planList = mutableListOf<Plan>()
            val creditList = mutableListOf<CreditPack>()
            try {
                val endpoint = "$BASE_URL/api/plans"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "GameNuke-App/3.6.0-Apeiron")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val responseStr = reader.use { it.readText() }
                    val json = JSONObject(responseStr)
                    val plansArr = json.optJSONArray("plans") ?: JSONArray()
                    for (i in 0 until plansArr.length()) {
                        val item = plansArr.getJSONObject(i)
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
                    val creditsArr = json.optJSONArray("creditPacks") ?: JSONArray()
                    for (i in 0 until creditsArr.length()) {
                        val item = creditsArr.getJSONObject(i)
                        creditList.add(
                            CreditPack(
                                id = item.getString("id"),
                                name = item.getString("name"),
                                credits = item.getInt("credits"),
                                price = item.getLong("price"),
                                badge = item.optString("badge", ""),
                                description = item.optString("description", "")
                            )
                        )
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}

            if (planList.isEmpty()) {
                planList.add(Plan("1_week", "Weekly VIP Pass", 7, 20000L, "Flexible Trial"))
                planList.add(Plan("1_month", "Monthly VIP Pass", 30, 50000L, "Most Popular"))
                planList.add(Plan("6_months", "Semi-Annual VIP Pass", 180, 180000L, "Save 25%"))
                planList.add(Plan("1_year", "Annual VIP Pass", 365, 290000L, "Best Value"))
            }

            if (creditList.isEmpty()) {
                creditList.add(CreditPack("credits_5", "5 VIP Credits (Starter)", 5, 3000L, "Starter Pack", "5 Game Boost Sessions (90m each)"))
                creditList.add(CreditPack("credits_10", "10 VIP Credits (Popular)", 10, 5000L, "Best Seller", "10 Boosts or 2x 24H VIP Passes"))
                creditList.add(CreditPack("credits_25", "25 VIP Credits (Gamer Value)", 25, 10000L, "Save 20%", "25 Boosts or 5x 24H VIP Passes"))
                creditList.add(CreditPack("credits_60", "60 VIP Credits (Titan Bonus)", 60, 20000L, "Max Value", "60 Boosts or 12x 24H VIP Passes"))
            }

            mainHandler.post {
                callback(PlansAndCreditsResult(plans = planList, creditPacks = creditList))
            }
        }
    }

    fun fetchPlansAsync(callback: (List<Plan>) -> Unit) {
        fetchPlansAndCreditsAsync { result ->
            callback(result.plans)
        }
    }

    fun consumeCreditsAsync(
        context: Context,
        action: String, // "boost_session" or "day_pass"
        callback: (ConsumeResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val deviceId = getDeviceId(appContext)

        executor.execute {
            var result: ConsumeResult
            try {
                val endpoint = "$BASE_URL/api/credits/consume"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "GameNuke-App/3.6.0-Apeiron")

                val payload = JSONObject()
                payload.put("deviceId", deviceId)
                payload.put("action", action)

                OutputStreamWriter(conn.outputStream).use {
                    it.write(payload.toString())
                    it.flush()
                }

                if (conn.responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val responseStr = reader.use { it.readText() }
                    val json = JSONObject(responseStr)

                    if (json.optBoolean("success", false)) {
                        // SERVER ADALAH SUMBER KEBENARAN.
                        // Nilai creditBalance dan expiresAt dari server WAJIB langsung disimpan.
                        // TIDAK ADA logika "pertahankan nilai lokal". Server selalu benar.
                        val newBalance = json.optInt("creditBalance", 0).coerceAtLeast(0)
                        // Server mengirim expiresAt setelah sesi ditambahkan — gunakan langsung.
                        val serverExpiresAt = json.optLong("expiresAt", 0L)
                        val sessionPassGranted = json.optBoolean("sessionPassGranted", false)

                        val sp = prefs(appContext)
                        // Gunakan expiresAt dari server jika valid (> 0), jika tidak pertahankan lokal
                        val finalExpiresAt = if (serverExpiresAt > 0L) serverExpiresAt else sp.getLong(KEY_EXPIRES_AT, 0L)

                        val seal = generateSeal(deviceId, finalExpiresAt, newBalance)
                        sp.edit()
                            .putInt(KEY_CREDIT_BALANCE, newBalance)
                            .putLong(KEY_EXPIRES_AT, finalExpiresAt)
                            .putString(KEY_PLAN_ID, "credit_session")
                            .putString(KEY_SIGNATURE_SEAL, seal)
                            .putLong(KEY_LAST_SYNC_TS, System.currentTimeMillis())
                            .apply()

                        val updatedStatus = getLocalStatus(appContext)
                        _subscriptionState.value = updatedStatus

                        result = ConsumeResult(
                            success = true,
                            newBalance = newBalance,
                            expiresAt = finalExpiresAt,
                            sessionPassGranted = sessionPassGranted
                        )
                    } else {
                        val msg = json.optString("message", json.optString("error", "Gagal menggunakan kredit"))
                        result = ConsumeResult(false, getCreditBalance(appContext), errorMessage = msg)
                    }
                } else {
                    val errReader = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream))
                    val errStr = errReader.use { it.readText() }
                    val msg = runCatching {
                        val errJson = JSONObject(errStr)
                        errJson.optString("message", errJson.optString("error", "Server error"))
                    }.getOrNull() ?: "Server error"
                    result = ConsumeResult(false, getCreditBalance(appContext), errorMessage = msg)
                }
                conn.disconnect()
            } catch (e: Exception) {
                result = ConsumeResult(false, getCreditBalance(appContext), errorMessage = e.message ?: "Connection error")
            }

            mainHandler.post {
                callback(result)
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
                conn.setRequestProperty("User-Agent", "GameNuke-App/3.6.0-Apeiron")

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
                    val planObj = json.optJSONObject("plan") ?: json.optJSONObject("creditPack")
                    val amount = planObj?.optLong("price") ?: json.optLong("amount", 0L)
                    val fee = json.optLong("fee", 0L)
                    val totalPayment = json.optLong("totalPayment", amount + fee)
                    val method = json.optString("paymentMethod", paymentMethod)
                    val qrisString = json.optString("qrisString").takeIf { !it.isNullOrBlank() }
                    val vaNumber = json.optString("vaNumber").takeIf { !it.isNullOrBlank() }
                    val vaBank = json.optString("vaBank", bank.uppercase()).takeIf { !it.isNullOrBlank() }
                    val paymentLink = json.optString("paymentLink").takeIf { !it.isNullOrBlank() }
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
                        paymentLink = paymentLink,
                        expiredAt = expiredAt
                    )

                    val planName = when (planId) {
                        "1_week" -> "Weekly VIP Pass"
                        "1_month" -> "Monthly VIP Pass"
                        "6_months" -> "Semi-Annual VIP Pass"
                        "1_year" -> "Annual VIP Pass"
                        "credits_5" -> "5 VIP Credits (Starter)"
                        "credits_10" -> "10 VIP Credits (Popular)"
                        "credits_25" -> "25 VIP Credits (Gamer Value)"
                        "credits_60" -> "60 VIP Credits (Titan Bonus)"
                        else -> if (planId.startsWith("credits_")) "${planId.removePrefix("credits_")} VIP Credits" else "VIP Pass"
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
                        paymentLink = paymentLink,
                        status = "PENDING",
                        createdAt = System.currentTimeMillis(),
                        expiresAt = System.currentTimeMillis() + 15 * 60 * 1000L
                    )
                    saveOrderToHistory(appContext, record)
                } else {
                    val errReader = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream))
                    val errStr = errReader.use { it.readText() }
                    val errMsg = runCatching { JSONObject(errStr).optString("error") }.getOrNull() ?: errStr
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
                        errorMessage = errMsg
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
                        paymentLink = obj.optString("paymentLink").takeIf { !it.isNullOrBlank() },
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
            obj.put("paymentLink", item.paymentLink ?: "")
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
                val devId = getDeviceId(appContext)
                val endpoint = "$BASE_URL/api/order/status?orderId=$orderId&deviceId=$devId&_t=${System.currentTimeMillis()}"
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
                        val serverCredits = json.optInt("creditBalance", -1)
                        if (serverCredits >= 0) {
                            val sp = prefs(appContext)
                            val devId = getDeviceId(appContext)
                            val serverExp = json.optLong("deviceExpiresAt", 0L)
                            val curExp = sp.getLong(KEY_EXPIRES_AT, 0L)
                            val expToSave = if (serverExp > 0L) serverExp else curExp
                            val seal = generateSeal(devId, expToSave, serverCredits)
                            sp.edit()
                                .putInt(KEY_CREDIT_BALANCE, serverCredits)
                                .putLong(KEY_EXPIRES_AT, expToSave)
                                .putString(KEY_SIGNATURE_SEAL, seal)
                                .apply()
                        }
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
