package com.neon.gametweak.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neon.gametweak.NukeSubscriptionManager
import com.neon.gametweak.NukeToast
import com.neon.gametweak.NukeTranslationManager
import com.neon.gametweak.nukePressFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun tr(text: String): String = NukeTranslationManager.tr(text)

/**
 * Enterprise Luxury Fintech Payment History Dialog.
 * Clean, dark obsidian layout, subtle borders, no garish rainbow colors.
 * All strings default to English and route through translation engine.
 */
@Composable
fun NukePaymentHistoryDialog(
    onDismiss: () -> Unit,
    onResumePayment: (NukeSubscriptionManager.OrderRecord) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var orders by remember { mutableStateOf(NukeSubscriptionManager.getOrderHistory(context)) }
    var isRefreshing by remember { mutableStateOf(false) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        NukeSubscriptionManager.syncDeviceOrdersAsync(context) { synced ->
            orders = synced
        }
        while (isActive) {
            delay(1000L)
            currentTime = System.currentTimeMillis()
        }
    }

    fun refreshOrders() {
        isRefreshing = true
        NukeSubscriptionManager.syncDeviceOrdersAsync(context) { synced ->
            orders = synced
            isRefreshing = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0A0D12))
                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Enterprise Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF111827))
                                .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.ReceiptLong,
                                contentDescription = null,
                                tint = Color(0xFFE2E8F0),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                tr("TRANSACTION HISTORY"),
                                color = Color(0xFFF8FAFC),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                tr("Orders & Subscription Invoices"),
                                color = Color(0xFF64748B),
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { refreshOrders() },
                            modifier = Modifier
                                .size(32.dp)
                                .nukePressFeedback()
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFF10B981),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = "Refresh",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .nukePressFeedback()
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Empty State or Order List
                if (orders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0D1117))
                            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                Icons.Rounded.HourglassBottom,
                                contentDescription = null,
                                tint = Color(0xFF475569),
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                tr("No Transaction History"),
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                tr("Your pending invoices and VIP subscriptions will appear here."),
                                color = Color(0xFF64748B),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((orders.size * 145).coerceAtMost(380).dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(orders, key = { it.orderId }) { order ->
                            OrderItemCard(
                                order = order,
                                currentTime = currentTime,
                                onResume = {
                                    onDismiss()
                                    onResumePayment(order)
                                },
                                onCancel = {
                                    scope.launch {
                                        NukeSubscriptionManager.cancelOrderAsync(context, order.orderId) { ok ->
                                            if (ok) {
                                                NukeToast.success(context, tr("Order successfully cancelled"))
                                            } else {
                                                NukeToast.warning(context, tr("Order status updated"))
                                            }
                                            refreshOrders()
                                        }
                                    }
                                },
                                onCheckStatus = {
                                    scope.launch {
                                        NukeSubscriptionManager.checkOrderStatusAsync(context, order.orderId) { resolved ->
                                            if (resolved == "COMPLETED" || resolved == "PAID") {
                                                NukeToast.success(context, tr("Payment Confirmed! VIP Pass Activated."))
                                            } else {
                                                NukeToast.info(context, "${tr("Status")}: $resolved")
                                            }
                                            refreshOrders()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    tr("Invoices are valid for 15 minutes. You can resume or verify your payment status anytime."),
                    color = Color(0xFF64748B),
                    fontSize = 9.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

@Composable
private fun OrderItemCard(
    order: NukeSubscriptionManager.OrderRecord,
    currentTime: Long,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onCheckStatus: () -> Unit
) {
    val context = LocalContext.current
    val formatRp = NumberFormat.getCurrencyInstance(Locale("in", "ID")).apply {
        maximumFractionDigits = 0
    }
    val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)

    val isPending = order.status.equals("PENDING", ignoreCase = true)
    val isCompleted = order.status.equals("COMPLETED", ignoreCase = true) || order.status.equals("PAID", ignoreCase = true)
    val isCancelled = order.status.equals("CANCELLED", ignoreCase = true)
    val isExplicitlyExpired = order.status.equals("EXPIRED", ignoreCase = true)

    val remainingMs = (order.expiresAt - currentTime).coerceAtLeast(0L)
    val isExpired = isExplicitlyExpired || (isPending && remainingMs <= 0L)

    if (isPending && remainingMs <= 0L) {
        LaunchedEffect(order.orderId) {
            NukeSubscriptionManager.updateOrderStatus(context, order.orderId, "EXPIRED")
        }
    }

    // Clean, subtle fintech status tags
    val (statusLabel, statusBg, statusText, statusBorder) = when {
        isCompleted -> Quad(tr("PAID"), Color(0xFF064E3B).copy(alpha = 0.5f), Color(0xFF34D399), Color(0xFF059669).copy(alpha = 0.5f))
        isCancelled -> Quad(tr("CANCELLED"), Color(0xFF1F2937), Color(0xFF94A3B8), Color(0xFF374151))
        isExpired -> Quad(tr("EXPIRED"), Color(0xFF3B151E).copy(alpha = 0.6f), Color(0xFFFB7185), Color(0xFFE11D48).copy(alpha = 0.5f))
        else -> Quad(tr("PENDING"), Color(0xFF451A03).copy(alpha = 0.5f), Color(0xFFFBBF24), Color(0xFFD97706).copy(alpha = 0.5f))
    }

    val remainingMinutes = (remainingMs / 1000) / 60
    val remainingSeconds = (remainingMs / 1000) % 60

    val methodDisplay = when (order.paymentMethod.lowercase()) {
        "bca_va" -> "BCA VA"
        "mandiri_va" -> "MANDIRI VA"
        "bri_va" -> "BRI VA"
        else -> "QRIS"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E131A))
            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Row 1: Plan Name + Method Badge + Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(order.planName),
                        color = Color(0xFFF1F5F9),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            methodDisplay,
                            color = Color(0xFF94A3B8),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusBg)
                        .border(0.8.dp, statusBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        statusLabel,
                        color = statusText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Row 2: Order ID + Copy
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.orderId,
                    color = Color(0xFF64748B),
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "Copy Order ID",
                    tint = Color(0xFF64748B),
                    modifier = Modifier
                        .size(11.dp)
                        .nukePressFeedback()
                        .clickable {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Order ID", order.orderId))
                            NukeToast.info(context, tr("Order ID copied"))
                        }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 3: Total Amount & Date / Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        formatRp.format(order.totalPayment),
                        color = Color(0xFFF8FAFC),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        dateFmt.format(Date(order.createdAt)),
                        color = Color(0xFF475569),
                        fontSize = 8.5.sp
                    )
                }

                if (isPending && !isExpired) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Countdown badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E293B))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                String.format(Locale.US, "%02d:%02d", remainingMinutes, remainingSeconds),
                                color = Color(0xFFFBBF24),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Pay Now Button (Clean gold accent)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFF59E0B))
                                .nukePressFeedback()
                                .clickable { onResume() }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                tr("Pay Now"),
                                color = Color.Black,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Check Status
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF111827))
                                .border(0.8.dp, Color(0xFF374151), RoundedCornerShape(6.dp))
                                .nukePressFeedback()
                                .clickable { onCheckStatus() }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                tr("Verify"),
                                color = Color(0xFFE2E8F0),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Cancel Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1F2937))
                                .nukePressFeedback()
                                .clickable { onCancel() }
                                .padding(horizontal = 7.dp, vertical = 5.dp)
                        ) {
                            Text(
                                tr("Cancel"),
                                color = Color(0xFF94A3B8),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else if (isExpired) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .border(0.8.dp, Color(0xFF374151), RoundedCornerShape(6.dp))
                            .nukePressFeedback()
                            .clickable { onResume() }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            tr("New Order"),
                            color = Color(0xFFF59E0B),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
