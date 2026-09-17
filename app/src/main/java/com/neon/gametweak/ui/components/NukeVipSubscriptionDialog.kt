package com.neon.gametweak.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HourglassDisabled
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.neon.gametweak.NukeSubscriptionManager
import com.neon.gametweak.NukeToast
import com.neon.gametweak.NukeTranslationManager
import com.neon.gametweak.nukePressFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private fun tr(text: String): String = NukeTranslationManager.tr(text)

private enum class DialogStep {
    SELECT_PLAN,
    PAYMENT,
    SUCCESS
}

data class PaymentOption(
    val id: String,
    val method: String,
    val bank: String,
    val label: String,
    val subtitle: String
)

val AVAILABLE_PAYMENT_OPTIONS = listOf(
    PaymentOption("qris", "qris", "all", "QRIS", "All E-Wallets & Banks"),
    PaymentOption("bca_va", "va", "bca", "BCA VA", "Bank Central Asia"),
    PaymentOption("mandiri_va", "va", "mandiri", "Mandiri VA", "Bank Mandiri"),
    PaymentOption("bri_va", "va", "bri", "BRI VA", "Bank Rakyat Indonesia")
)

/**
 * Enterprise Minimalist Responsive VIP Subscription Dialog.
 * Compact layout designed to adapt gracefully to any screen height and width.
 * Automatic expiration handling with clear expired invoice states.
 */
@Composable
fun NukeVipSubscriptionDialog(
    onDismiss: () -> Unit,
    initialOrder: NukeSubscriptionManager.OrderRecord? = null,
    onOpenPaymentHistory: () -> Unit = {},
    onSubscribed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentStep by remember {
        mutableStateOf(if (initialOrder != null) DialogStep.PAYMENT else DialogStep.SELECT_PLAN)
    }
    var plans by remember { mutableStateOf<List<NukeSubscriptionManager.Plan>>(emptyList()) }
    var selectedPlanId by remember { mutableStateOf(initialOrder?.planId ?: "1_month") }
    var selectedPaymentOptionId by remember {
        mutableStateOf(initialOrder?.paymentMethod?.takeIf { it.isNotBlank() } ?: "qris")
    }
    var isLoading by remember { mutableStateOf(false) }

    var orderResult by remember {
        mutableStateOf<NukeSubscriptionManager.OrderResult?>(
            initialOrder?.let {
                NukeSubscriptionManager.OrderResult(
                    success = true,
                    orderId = it.orderId,
                    amount = it.amount,
                    fee = it.fee,
                    totalPayment = it.totalPayment,
                    paymentMethod = it.paymentMethod,
                    qrisString = it.qrisString,
                    vaNumber = it.vaNumber,
                    vaBank = it.vaBank,
                    expiredAt = null
                )
            }
        )
    }

    var qrBitmap by remember {
        mutableStateOf<Bitmap?>(initialOrder?.qrisString?.let { generateQrBitmap(it) })
    }

    var countdownSeconds by remember {
        mutableIntStateOf(
            if (initialOrder != null) {
                if (initialOrder.status.equals("EXPIRED", ignoreCase = true)) {
                    0
                } else {
                    ((initialOrder.expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt()
                }
            } else 900
        )
    }

    val subStatus by NukeSubscriptionManager.subscriptionState.collectAsState()
    val isAlreadyVip = subStatus.isActive
    val remainingDays = subStatus.remainingDays

    LaunchedEffect(Unit) {
        NukeSubscriptionManager.syncStatusIfNeeded(context, force = true)
        NukeSubscriptionManager.fetchPlansAsync { fetched ->
            plans = fetched
            if (fetched.isNotEmpty() && fetched.none { it.id == selectedPlanId }) {
                selectedPlanId = fetched.first().id
            }
        }
    }

    // Auto-poll status while order is active and not expired
    LaunchedEffect(currentStep, orderResult, countdownSeconds) {
        if (currentStep == DialogStep.PAYMENT && orderResult != null && countdownSeconds > 0) {
            val oid = orderResult?.orderId
            while (isActive && countdownSeconds > 0 && currentStep == DialogStep.PAYMENT) {
                delay(3500L)
                if (oid != null) {
                    NukeSubscriptionManager.checkOrderStatusAsync(context, oid) { st ->
                        if (st == "COMPLETED" || st == "PAID") {
                            currentStep = DialogStep.SUCCESS
                            onSubscribed()
                        } else if (st == "EXPIRED") {
                            countdownSeconds = 0
                        }
                    }
                } else {
                    NukeSubscriptionManager.syncStatusIfNeeded(context, force = true) { status ->
                        if (status.isActive) {
                            currentStep = DialogStep.SUCCESS
                            onSubscribed()
                        }
                    }
                }
            }
        }
    }

    // Countdown ticker & auto-expiry updater
    LaunchedEffect(currentStep, countdownSeconds) {
        if (currentStep == DialogStep.PAYMENT && countdownSeconds > 0) {
            delay(1000L)
            countdownSeconds--
            if (countdownSeconds <= 0 && orderResult?.orderId != null) {
                // Mark locally as EXPIRED immediately
                NukeSubscriptionManager.updateOrderStatus(context, orderResult!!.orderId!!, "EXPIRED")
            }
        }
    }

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val maxDialogHeight = (screenHeight * 0.85f).coerceAtMost(560.dp)

    val handleDismiss = {
        if (currentStep == DialogStep.PAYMENT && orderResult?.orderId != null) {
            NukeSubscriptionManager.cancelOrderAsync(context, orderResult!!.orderId!!) {}
        }
        onDismiss()
    }

    Dialog(
        onDismissRequest = handleDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 420.dp)
                .heightIn(max = maxDialogHeight)
                .wrapContentHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF070B0E))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header (Compact)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF59E0B).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.WorkspacePremium,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                tr("GAME NUKE VIP"),
                                color = Color(0xFFF59E0B),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                if (isAlreadyVip) "${tr("ACTIVE")} ($remainingDays ${tr("Days Left")})" else tr("100% AD-FREE PASS"),
                                color = if (isAlreadyVip) Color(0xFF10B981) else Color(0xFF94A3B8),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(
                        onClick = handleDismiss,
                        modifier = Modifier
                            .size(28.dp)
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

                Spacer(Modifier.height(10.dp))

                when (currentStep) {
                    DialogStep.SELECT_PLAN -> {
                        // Clean 2x2 Value Proposition Chips (Never cram or wrap)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactChip("✓ " + tr("Zero Ads (100% Clean)"), Modifier.weight(1f))
                            CompactChip("✓ " + tr("All VIP Tools Unlocked"), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactChip("✓ " + tr("Instant HWID Lock"), Modifier.weight(1f))
                            CompactChip("✓ " + tr("QRIS & Multi-Bank VA"), Modifier.weight(1f))
                        }

                        Spacer(Modifier.height(10.dp))

                        if (isAlreadyVip) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0F241A))
                                    .border(1.dp, Color(0xFF10B981).copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF10B981).copy(alpha = 0.25f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Rounded.WorkspacePremium,
                                                    contentDescription = null,
                                                    tint = Color(0xFFFFB830),
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    tr("ACTIVE VIP SUBSCRIPTION"),
                                                    color = Color(0xFFFFB830),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Black,
                                                    letterSpacing = 0.5.sp
                                                )
                                                Text(
                                                    "$remainingDays ${tr("Days Remaining")} · ${tr("100% Ad-Free")}",
                                                    color = Color(0xFF34D399),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                                .border(0.5.dp, Color(0xFF10B981), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                tr("ACTIVE"),
                                                color = Color(0xFF10B981),
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF064E3B).copy(alpha = 0.35f))
                                            .border(0.8.dp, Color(0xFF10B981).copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.Info, null, tint = Color(0xFF34D399), modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                tr("Extend or Upgrade: Purchasing any tier below will ADD extra days directly on top of your remaining days. Zero days are lost!"),
                                                color = Color(0xFFD1FAE5),
                                                fontSize = 8.5.sp,
                                                lineHeight = 11.5.sp
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        // Plan Selector
                        Text(
                            tr("SELECT SUBSCRIPTION TIER"),
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(6.dp))

                        if (plans.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(70.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFFF59E0B),
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                plans.forEach { plan ->
                                    val isSelected = plan.id == selectedPlanId
                                    PlanCardCompact(
                                        plan = plan,
                                        isSelected = isSelected,
                                        onClick = { selectedPlanId = plan.id }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Payment Method Selector
                        Text(
                            tr("SELECT PAYMENT METHOD"),
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(6.dp))

                        // Responsive 2x2 Payment Method Grid (Generous width per option, prevents text wrapping)
                        val paymentOptionChunks = AVAILABLE_PAYMENT_OPTIONS.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            paymentOptionChunks.forEach { rowOpts ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowOpts.forEach { opt ->
                                        val isSelected = opt.id == selectedPaymentOptionId
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) Color(0xFF1E293B) else Color(0xFF0D1219))
                                                .border(
                                                    1.dp,
                                                    if (isSelected) Color(0xFFF59E0B) else Color(0xFF1E293B),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .nukePressFeedback()
                                                .clickable { selectedPaymentOptionId = opt.id }
                                                .padding(vertical = 7.dp, horizontal = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    if (opt.method == "qris") Icons.Rounded.QrCode else Icons.Rounded.AccountBalance,
                                                    contentDescription = null,
                                                    tint = if (isSelected) Color(0xFFF59E0B) else Color(0xFF94A3B8),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Column {
                                                    Text(
                                                        opt.label,
                                                        color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                                        fontSize = 9.5.sp,
                                                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        if (opt.method == "qris") tr("E-Wallet / Bank") else tr("Virtual Account"),
                                                        color = if (isSelected) Color(0xFFF59E0B).copy(alpha = 0.8f) else Color(0xFF64748B),
                                                        fontSize = 7.5.sp,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // International User Fallback Notice & Live Chat Action
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E293B).copy(alpha = 0.6f))
                                .border(0.8.dp, Color(0xFF38BDF8).copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.Info,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        tr("Outside Indonesia? Buy VIP via Live Chat"),
                                        color = Color(0xFF38BDF8),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    tr("Automated checkout supports Indonesian QRIS and Bank VA. International gamers can purchase VIP directly by chatting with our support."),
                                    color = Color(0xFF94A3B8),
                                    fontSize = 8.sp,
                                    lineHeight = 11.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF0284C7).copy(alpha = 0.25f))
                                        .border(0.5.dp, Color(0xFF38BDF8), RoundedCornerShape(4.dp))
                                        .nukePressFeedback()
                                        .clickable {
                                            onDismiss()
                                            com.neon.gametweak.NukeLiveChatOverlay.getInstance(context).show()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "💬 " + tr("Open Live Chat Support"),
                                        color = Color(0xFF38BDF8),
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Button(
                            onClick = {
                                val selectedOpt = AVAILABLE_PAYMENT_OPTIONS.firstOrNull { it.id == selectedPaymentOptionId }
                                    ?: AVAILABLE_PAYMENT_OPTIONS.first()
                                isLoading = true
                                NukeSubscriptionManager.createOrderAsync(
                                    context = context,
                                    planId = selectedPlanId,
                                    paymentMethod = selectedOpt.id,
                                    bank = selectedOpt.bank
                                ) { res ->
                                    isLoading = false
                                    if (res.success) {
                                        orderResult = res
                                        qrBitmap = res.qrisString?.let { generateQrBitmap(it) }
                                        countdownSeconds = 900
                                        currentStep = DialogStep.PAYMENT
                                    } else {
                                        NukeToast.error(context, res.errorMessage ?: tr("Failed to initiate payment."))
                                    }
                                }
                            },
                            enabled = !isLoading && plans.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF59E0B),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .nukePressFeedback()
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.Black,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    if (isAlreadyVip) tr("EXTEND / UPGRADE SUBSCRIPTION") else tr("CONTINUE TO PAYMENT"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Text(
                            tr("View Payment History"),
                            color = Color(0xFF38BDF8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .nukePressFeedback()
                                .clickable {
                                    onDismiss()
                                    onOpenPaymentHistory()
                                }
                                .padding(vertical = 4.dp)
                        )
                    }

                    DialogStep.PAYMENT -> {
                        val res = orderResult
                        if (res != null) {
                            val formattedTotal = formatRupiah(res.totalPayment)
                            val isQrPayment = res.paymentMethod.equals("qris", ignoreCase = true)
                            val isExpired = countdownSeconds <= 0

                            if (isExpired) {
                                // ── INVOICE EXPIRED VIEW ──
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF1F2937).copy(alpha = 0.5f))
                                        .border(1.dp, Color(0xFF475569), RoundedCornerShape(10.dp))
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Rounded.HourglassDisabled,
                                            contentDescription = null,
                                            tint = Color(0xFFF43F5E),
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            tr("INVOICE EXPIRED"),
                                            color = Color(0xFFF43F5E),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            tr("This payment session has timed out (15-minute limit). Please initiate a new order."),
                                            color = Color(0xFF94A3B8),
                                            fontSize = 9.5.sp,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 13.sp
                                        )
                                    }
                                }

                                Spacer(Modifier.height(14.dp))

                                Button(
                                    onClick = { currentStep = DialogStep.SELECT_PLAN },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFF59E0B),
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .nukePressFeedback()
                                ) {
                                    Text(tr("CREATE NEW ORDER"), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                }
                            } else {
                                // ── ACTIVE PAYMENT VIEW ──
                                val minutes = countdownSeconds / 60
                                val seconds = countdownSeconds % 60
                                val timerText = String.format(Locale.US, "%02d:%02d", minutes, seconds)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF141C24))
                                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(tr("Expires in:"), color = Color(0xFF94A3B8), fontSize = 9.5.sp)
                                    Text(timerText, color = Color(0xFFF59E0B), fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                                }

                                Spacer(Modifier.height(10.dp))

                                if (isQrPayment) {
                                    // QR Code Container (Clickable to save)
                                    Box(
                                        modifier = Modifier
                                            .size(135.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White)
                                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                val ok = saveQrisToGallery(context, res.qrisString ?: "", res.orderId ?: "", res.totalPayment.toLong())
                                                if (ok) {
                                                    Toast.makeText(context, tr("QRIS image saved to Gallery! Open your e-wallet to scan from photo."), Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, tr("Failed to save QRIS image"), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (qrBitmap != null) {
                                            Image(
                                                bitmap = qrBitmap!!.asImageBitmap(),
                                                contentDescription = "QRIS Code",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(Icons.Rounded.QrCode, null, tint = Color.Black, modifier = Modifier.size(28.dp))
                                                Text(tr("Generating QRIS..."), color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    // Download QRIS Action Button
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF1E293B))
                                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                val ok = saveQrisToGallery(context, res.qrisString ?: "", res.orderId ?: "", res.totalPayment.toLong())
                                                if (ok) {
                                                    Toast.makeText(context, tr("QRIS image saved to Gallery! Open your e-wallet to scan from photo."), Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, tr("Failed to save QRIS image"), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(vertical = 7.dp, horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Download, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            tr("DOWNLOAD QRIS (SAVE TO GALLERY)"),
                                            color = Color(0xFFF59E0B),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }

                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        tr("Download QRIS to scan from your e-wallet gallery or point camera."),
                                        color = Color(0xFF94A3B8),
                                        fontSize = 8.5.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 11.sp
                                    )
                                } else {
                                    // Virtual Account Card (Compact)
                                    val bankName = res.vaBank ?: "VIRTUAL ACCOUNT"
                                    val vaNumber = res.vaNumber ?: "88908${(res.amount % 90000) + 10000}"

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0E1722))
                                            .border(1.dp, Color(0xFF2563EB).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                            .padding(10.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                "${bankName.uppercase()} VIRTUAL ACCOUNT",
                                                color = Color(0xFF93C5FD),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF1E293B))
                                                    .nukePressFeedback()
                                                    .clickable {
                                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        cm.setPrimaryClip(ClipData.newPlainText("VA Number", vaNumber))
                                                        NukeToast.success(context, "${tr("Copied VA Number")}: $vaNumber")
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    vaNumber,
                                                    color = Color.White,
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Black,
                                                    fontFamily = FontFamily.Monospace,
                                                    letterSpacing = 1.sp
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Icon(Icons.Rounded.ContentCopy, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                tr("Open Mobile Banking > Transfer > Virtual Account > Paste VA Number"),
                                                color = Color(0xFF64748B),
                                                fontSize = 8.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                // Exact Fee & Total (Compact)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0E131A))
                                        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        PriceRowCompact(tr("Subtotal (Plan)"), formatRupiah(res.amount))
                                        if (res.fee > 0) {
                                            PriceRowCompact(
                                                "${tr("Gateway Fee")} (${if (isQrPayment) "QRIS" else "VA"})",
                                                formatRupiah(res.fee)
                                            )
                                        }
                                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F2937)))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                tr("EXACT TOTAL TO PAY"),
                                                color = Color.White,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    formattedTotal,
                                                    color = Color(0xFFF59E0B),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                IconButton(
                                                    onClick = {
                                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        cm.setPrimaryClip(ClipData.newPlainText("GameNuke Total", res.totalPayment.toString()))
                                                        NukeToast.success(context, "${tr("Copied total amount")}: $formattedTotal")
                                                    },
                                                    modifier = Modifier.size(20.dp).nukePressFeedback()
                                                ) {
                                                    Icon(Icons.Rounded.ContentCopy, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(13.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // Compact Notice
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF78350F).copy(alpha = 0.2f))
                                        .border(0.8.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                        .padding(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            Icons.Rounded.WarningAmber,
                                            contentDescription = null,
                                            tint = Color(0xFFFBBF24),
                                            modifier = Modifier.size(13.dp).padding(top = 1.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            tr("IMPORTANT: You must transfer the EXACT total amount above (including fees / unique digits). Any difference will cause payment gateway verification to fail."),
                                            color = Color(0xFFFDE68A),
                                            fontSize = 8.sp,
                                            lineHeight = 11.sp
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                // Actions
                                Button(
                                    onClick = {
                                        val oid = orderResult?.orderId
                                        if (oid != null) {
                                            NukeSubscriptionManager.checkOrderStatusAsync(context, oid) { st ->
                                                if (st == "COMPLETED" || st == "PAID") {
                                                    currentStep = DialogStep.SUCCESS
                                                    onSubscribed()
                                                } else if (st == "EXPIRED") {
                                                    countdownSeconds = 0
                                                    NukeToast.error(context, tr("Order has expired. Please create a new order."))
                                                } else {
                                                    NukeToast.info(context, tr("Payment pending. Awaiting gateway settlement..."))
                                                }
                                            }
                                        } else {
                                            NukeSubscriptionManager.syncStatusIfNeeded(context, force = true) { status ->
                                                if (status.isActive) {
                                                    currentStep = DialogStep.SUCCESS
                                                    onSubscribed()
                                                } else {
                                                    NukeToast.info(context, tr("Payment pending. Awaiting gateway settlement..."))
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFF59E0B),
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                        .nukePressFeedback()
                                ) {
                                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(tr("CHECK PAYMENT STATUS"), fontSize = 10.5.sp, fontWeight = FontWeight.Black)
                                }

                                Spacer(Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val oid = orderResult?.orderId
                                            if (oid != null) {
                                                NukeSubscriptionManager.cancelOrderAsync(context, oid) {}
                                            }
                                            currentStep = DialogStep.SELECT_PLAN
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .nukePressFeedback()
                                    ) {
                                        Text(tr("CHANGE PLAN"), fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val oid = orderResult?.orderId
                                            if (oid != null) {
                                                NukeSubscriptionManager.cancelOrderAsync(context, oid) {}
                                            }
                                            orderResult = null
                                            currentStep = DialogStep.SELECT_PLAN
                                            NukeToast.info(context, tr("Invoice cancelled."))
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .nukePressFeedback()
                                    ) {
                                        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(12.dp), tint = Color(0xFFEF4444))
                                        Spacer(Modifier.width(3.dp))
                                        Text(tr("CANCEL ORDER"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    DialogStep.SUCCESS -> {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF10B981), modifier = Modifier.size(34.dp))
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            tr("VIP UNLOCKED!"),
                            color = Color(0xFFF59E0B),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tr("Your VIP subscription has been successfully activated. All ads and booster gates are permanently disabled on this device."),
                            color = Color(0xFFE2E8F0),
                            fontSize = 9.5.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 13.sp
                        )

                        Spacer(Modifier.height(14.dp))

                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .nukePressFeedback()
                        ) {
                            Text(tr("DONE"), fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF111827))
            .border(0.8.dp, Color(0xFF1F2937), RoundedCornerShape(6.dp))
            .padding(vertical = 5.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color(0xFF94A3B8),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlanCardCompact(
    plan: NukeSubscriptionManager.Plan,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFFF59E0B) else Color(0xFF1E293B)
    val bgColor = if (isSelected) Color(0xFF161E28) else Color(0xFF0D1219)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .nukePressFeedback()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(plan.name),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (plan.badge.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFF59E0B).copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            tr(plan.badge),
                            color = Color(0xFFF59E0B),
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${plan.durationDays} ${tr("Days Duration")}",
                color = Color(0xFF64748B),
                fontSize = 8.5.sp,
                maxLines = 1
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            formatRupiah(plan.price),
            color = if (isSelected) Color(0xFFF59E0B) else Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PriceRowCompact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 9.sp)
        Text(value, color = Color(0xFFE2E8F0), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatRupiah(amount: Long): String {
    val format = NumberFormat.getCurrencyInstance(Locale("in", "ID"))
    format.maximumFractionDigits = 0
    return format.format(amount)
}

private fun generateQrBitmap(content: String, size: Int = 512, margin: Int = 1): Bitmap? {
    return try {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.MARGIN] = margin
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        hints[EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}

private fun saveQrisToGallery(
    context: Context,
    qrisContent: String,
    orderId: String,
    totalPayment: Long
): Boolean {
    if (qrisContent.isBlank()) return false
    return try {
        val qrBmp = generateQrBitmap(qrisContent, 720, 1) ?: return false

        val cardWidth = 840
        val cardHeight = 1120
        val cardBmp = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cardBmp)

        canvas.drawColor(AndroidColor.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            isFilterBitmap = true
        }

        // Header Banner (QRIS Official Red)
        paint.color = AndroidColor.parseColor("#C62828")
        canvas.drawRect(0f, 0f, cardWidth.toFloat(), 120f, paint)

        // Header Title
        paint.color = AndroidColor.WHITE
        paint.textSize = 40f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("QRIS PEMBAYARAN NASIONAL", cardWidth / 2f, 75f, paint)

        // Total Payment Container
        paint.color = AndroidColor.parseColor("#F1F5F9")
        canvas.drawRect(40f, 140f, (cardWidth - 40).toFloat(), 250f, paint)

        paint.color = AndroidColor.parseColor("#64748B")
        paint.textSize = 24f
        paint.isFakeBoldText = false
        canvas.drawText("TOTAL PEMBAYARAN PAS", cardWidth / 2f, 180f, paint)

        paint.color = AndroidColor.parseColor("#0F172A")
        paint.textSize = 48f
        paint.isFakeBoldText = true
        val formattedAmount = formatRupiah(totalPayment)
        canvas.drawText(formattedAmount, cardWidth / 2f, 235f, paint)

        // Draw QR Code
        val qrLeft = (cardWidth - 720) / 2f
        val qrTop = 270f
        canvas.drawBitmap(qrBmp, qrLeft, qrTop, paint)

        // Draw Border Around QR
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = AndroidColor.parseColor("#CBD5E1")
        canvas.drawRect(qrLeft - 8f, qrTop - 8f, qrLeft + 728f, qrTop + 728f, paint)
        paint.style = Paint.Style.FILL

        // Footer Order Info
        paint.color = AndroidColor.parseColor("#1E293B")
        paint.textSize = 26f
        paint.isFakeBoldText = true
        canvas.drawText("ORDER ID: $orderId", cardWidth / 2f, 1030f, paint)

        paint.color = AndroidColor.parseColor("#64748B")
        paint.textSize = 21f
        paint.isFakeBoldText = false
        canvas.drawText("Scan dari Galeri di GoPay, OVO, DANA, BCA, Mandiri, ShopeePay", cardWidth / 2f, 1070f, paint)

        val cleanId = orderId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val filename = "QRIS_${cleanId}_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GameNuke")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                cardBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            true
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }
}
