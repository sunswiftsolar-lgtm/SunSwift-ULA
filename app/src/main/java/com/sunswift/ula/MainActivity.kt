package com.sunswift.ula

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private val UlaNavy = Color(0xFF17324D)
private val UlaOrange = Color(0xFFD64A2F)
private val UlaGreen = Color(0xFF147D64)
private val UlaBlue = Color(0xFF326EA8)
private val UlaBackground = Color(0xFFF3F5F7)
private val UlaError = Color(0xFF9D2D20)
private val UlaInk = Color(0xFF18212B)
private val UlaMuted = Color(0xFF667085)
private val UlaLine = Color(0xFFD8DEE6)
private const val ANDROID_VERSION = BuildConfig.ULA_ANDROID_VERSION
private const val WEB_REFERENCE_VERSION = BuildConfig.ULA_WEB_VERSION
private const val OTP_VALIDITY_SECONDS = 300

class MainActivity : ComponentActivity() {
    private lateinit var appUpdates: UlaAppUpdates
    private val updateLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (::appUpdates.isInitialized) appUpdates.onFlowResult(result.resultCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val updateManager = AppUpdateManagerFactory.create(this)
        appUpdates = UlaAppUpdates(this, updateManager) { info ->
            updateManager.startUpdateFlowForResult(info, updateLauncher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = UlaOrange,
                    secondary = UlaNavy,
                    tertiary = UlaGreen,
                    background = UlaBackground,
                    surface = Color.White,
                    error = UlaError
                )
            ) {
                CompositionLocalProvider(LocalUlaAppUpdates provides appUpdates) {
                    UlaNativeApp(this)
                    AppUpdateDialog(appUpdates)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::appUpdates.isInitialized) appUpdates.check()
    }

    override fun onDestroy() {
        if (::appUpdates.isInitialized) appUpdates.dispose()
        super.onDestroy()
    }
}

@Composable
private fun UlaNativeApp(activity: Activity, ulaViewModel: UlaViewModel = viewModel()) {
    val state = ulaViewModel.state
    when {
        state.checkingSession -> SessionLoadingScreen()
        state.user == null -> NativePhoneLogin(
            activity = activity,
            auth = FirebaseAuth.getInstance(),
            serverError = state.error,
            checkLoginMobile = ulaViewModel::checkLoginMobile,
            onSignedIn = ulaViewModel::authorizeSignedInUser
        )
        else -> UlaWebScreen(state = state, onLogout = ulaViewModel::logout)
    }
}

@Composable
private fun SessionLoadingScreen() {
    Surface(Modifier.fillMaxSize(), color = UlaBackground) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AppMark()
            Spacer(Modifier.height(22.dp))
            Text(if (BuildConfig.DEBUG) "SunSwift ULA TEST" else "SunSwift ULA", color = UlaNavy)
            Spacer(Modifier.height(8.dp))
            Text("Connecting securely to ULA…", color = UlaNavy, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppUpdatesLink()
                PrivacyPolicyLink()
            }
        }
    }
}

@Composable
private fun AppMark() {
    Surface(
        modifier = Modifier.size(86.dp),
        shape = RoundedCornerShape(28.dp),
        color = UlaNavy
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.ElectricBolt,
                contentDescription = null,
                tint = UlaOrange,
                modifier = Modifier.size(50.dp)
            )
        }
    }
}

@Composable
private fun NativePhoneLogin(
    activity: Activity,
    auth: FirebaseAuth,
    serverError: String?,
    checkLoginMobile: suspend (String) -> UlaLoginAccess,
    onSignedIn: () -> Unit
) {
    var mobile by remember { mutableStateOf("") }
    var privacyConsent by rememberSaveable { mutableStateOf(false) }
    var otp by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(serverError ?: "Checking authentication configuration…") }
    var otpSecondsRemaining by remember { mutableIntStateOf(0) }
    var otpDeadline by remember { mutableLongStateOf(0L) }
    var otpQuota by remember { mutableStateOf<UlaOtpQuota?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverError) {
        if (!serverError.isNullOrBlank()) message = serverError
    }

    fun finishSignIn(credential: PhoneAuthCredential) {
        if (otpDeadline > 0 && SystemClock.elapsedRealtime() >= otpDeadline) {
            loading = false
            otpSecondsRemaining = 0
            message = "This OTP has expired. Request a new OTP to continue."
            return
        }
        loading = true
        auth.signInWithCredential(credential).addOnCompleteListener { task ->
            loading = false
            if (task.isSuccessful) {
                message = "OTP verified. Checking your ULA role…"
                keyboard?.hide()
                onSignedIn()
            } else {
                message = task.exception?.localizedMessage ?: "OTP verification failed"
            }
        }
    }

    val callbacks = remember {
        object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                finishSignIn(credential)
            }

            override fun onVerificationFailed(error: FirebaseException) {
                loading = false
                message = error.localizedMessage ?: "Phone verification failed"
            }

            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                loading = false
                verificationId = id
                otpDeadline = SystemClock.elapsedRealtime() + OTP_VALIDITY_SECONDS * 1_000L
                otpSecondsRemaining = OTP_VALIDITY_SECONDS
                message = "OTP sent to +91 $mobile. Enter the 6-digit code."
            }
        }
    }

    LaunchedEffect(verificationId, otpDeadline) {
        while (verificationId != null && otpSecondsRemaining > 0) {
            otpSecondsRemaining = ((otpDeadline - SystemClock.elapsedRealtime() + 999L) / 1_000L).coerceAtLeast(0).toInt()
            delay(1_000)
        }
        if (verificationId != null && otpSecondsRemaining == 0) {
            message = "This OTP has expired. Request a new OTP to continue."
        }
    }

    val otpExpired = verificationId != null && otpSecondsRemaining <= 0
    val statusIsError = serverError != null || message.contains("failed", true) ||
        message.contains("invalid", true) || message.contains("expired", true) ||
        message.contains("not authorised", true) || message.contains("not authorized", true)

    Surface(Modifier.fillMaxSize(), color = UlaBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.horizontalGradient(listOf(Color(0xFF112A42), Color(0xFF21486A)))
                ).statusBarsPadding().padding(horizontal = 22.dp, vertical = 24.dp)
            ) {
                Column {
                    Text("ULA/1KW Data Management", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Cloud registration, installation tracking and consent report generation",
                        color = Color.White.copy(alpha = .78f), fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Android v$ANDROID_VERSION • Web v$WEB_REFERENCE_VERSION reference", color = Color(0xFFFFB19F), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, UlaLine),
                elevation = CardDefaults.cardElevation(2.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("ULA Mobile OTP Login", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = UlaNavy)
                    Spacer(Modifier.height(6.dp))
                    Text("Sign in with a mobile number authorized by the ULA Admin.", color = UlaMuted, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        message,
                        color = if (statusIsError) UlaError else UlaMuted,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        AppUpdatesLink()
                        PrivacyPolicyLink()
                    }

                    if (verificationId == null) {
                        OutlinedTextField(
                            value = mobile,
                            onValueChange = {
                                val updatedMobile = it.filter(Char::isDigit).take(10)
                                if (updatedMobile != mobile) privacyConsent = false
                                mobile = updatedMobile
                            },
                            enabled = !loading,
                            label = { Text("Mobile number") },
                            leadingIcon = { Text("+91", fontWeight = FontWeight.Bold) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Done
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(ULA_PHONE_PRIVACY_DISCLOSURE, color = UlaMuted, fontSize = 12.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = privacyConsent,
                                enabled = !loading,
                                role = Role.Checkbox,
                                onValueChange = { privacyConsent = it }
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = privacyConsent, onCheckedChange = null, enabled = !loading)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "I agree to the phone-verification processing and ULA Privacy Policy.",
                                color = UlaInk, fontSize = 12.sp, modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = {
                                keyboard?.hide()
                                if (!privacyConsent) {
                                    message = "Read the Privacy Policy and agree to phone verification before requesting an OTP."
                                    return@Button
                                }
                                if (mobile.length != 10) {
                                    message = "Enter a valid 10-digit mobile number"
                                    return@Button
                                }
                                scope.launch {
                                    loading = true
                                    message = "Checking ULA authorization…"
                                    try {
                                        val access = checkLoginMobile(mobile)
                                        when {
                                            !access.configured -> {
                                                otpQuota = null
                                                loading = false
                                                message = "ULA OTP login is not configured. Contact the administrator."
                                            }
                                            !access.authorized -> {
                                                otpQuota = null
                                                loading = false
                                                message = "This mobile number is not authorized. Ask the ULA Admin to add it in User control."
                                            }
                                            else -> {
                                                otpQuota = access.quota
                                                message = "Authorized. Requesting OTP…"
                                                PhoneAuthProvider.verifyPhoneNumber(
                                                    PhoneAuthOptions.newBuilder(auth)
                                                        .setPhoneNumber("+91$mobile")
                                                        .setTimeout(60L, TimeUnit.SECONDS)
                                                        .setActivity(activity)
                                                        .setCallbacks(callbacks)
                                                        .build()
                                                )
                                            }
                                        }
                                    } catch (error: Exception) {
                                        loading = false
                                        message = error.localizedMessage ?: "ULA authorization could not be checked."
                                    }
                                }
                            },
                            enabled = !loading && privacyConsent && mobile.length == 10,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Send OTP", fontSize = 16.sp)
                        }
                    } else {
                        OutlinedTextField(
                            value = otp,
                            onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                            label = { Text("OTP") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (otpExpired) "OTP expired" else "OTP expires in ${otpSecondsRemaining / 60}:${(otpSecondsRemaining % 60).toString().padStart(2, '0')}",
                            color = if (otpExpired) UlaError else UlaGreen,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                keyboard?.hide()
                                if (otp.length == 6) {
                                    finishSignIn(PhoneAuthProvider.getCredential(verificationId!!, otp))
                                }
                            },
                            enabled = !loading && !otpExpired && otp.length == 6,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Verify & Login", fontSize = 16.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                verificationId = null
                                otp = ""
                                otpSecondsRemaining = 0
                                otpDeadline = 0L
                                message = "Enter your authorized mobile number."
                            }) { Text("Change Number") }
                            if (otpExpired) {
                                TextButton(onClick = {
                                    verificationId = null
                                    otp = ""
                                    otpSecondsRemaining = 0
                                    otpDeadline = 0L
                                    message = "Request a new OTP."
                                }) { Text("Request new OTP") }
                            }
                        }
                    }

                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    otpQuota?.let { quota ->
                        Text(
                            "Your OTPs remaining today: ${quota.remaining} of ${quota.limit} • Project balance: ${quota.projectRemaining} of ${quota.projectLimit}",
                            color = UlaGreen,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = UlaLine)
                    Text(
                        "Your verified session stays open until logout or the server-set expiry. A new OTP is required after expiry.",
                        color = UlaMuted, fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UlaShell(
    state: UlaAppState,
    onPage: (AppPage) -> Unit,
    onRecord: (UlaRecord) -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onRefreshRecords: () -> Unit,
    onRefreshActivity: () -> Unit,
    onSaveUser: (String, String, String) -> Unit,
    onSetUserActive: (String, Boolean) -> Unit,
    onSetUserRole: (String, String) -> Unit,
    onRemoveUser: (String) -> Unit,
    onAddInstaller: (String) -> Unit,
    onRemoveInstaller: (String) -> Unit,
    onDismissError: () -> Unit,
    onLogout: () -> Unit
) {
    val user = state.user ?: return
    var showActivity by remember { mutableStateOf(false) }
    val pages = buildList {
        add(AppPage.Dashboard)
        add(AppPage.Records)
        if (user.canEdit) add(AppPage.RecordForm)
        add(AppPage.Reports)
        if (user.canAdmin) add(AppPage.Access)
    }

    Scaffold(
        containerColor = UlaBackground,
        topBar = {
            Column(Modifier.background(Color.White)) {
                Row(
                    Modifier.fillMaxWidth().background(
                        Brush.horizontalGradient(listOf(Color(0xFF112A42), Color(0xFF21486A)))
                    ).statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 13.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("ULA/1KW Data Management", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "${user.name} • ${user.role} • Session until 11:59 PM IST",
                            fontSize = 11.sp, color = Color.White.copy(alpha = .75f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("v$ANDROID_VERSION • Web v$WEB_REFERENCE_VERSION", fontSize = 10.sp, color = Color(0xFFFFB19F))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (user.canAdmin) {
                                TextButton(
                                    onClick = {
                                        onRefreshActivity()
                                        showActivity = true
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Icon(Icons.Default.ManageHistory, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Activity", fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { onPage(AppPage.Access) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Icon(Icons.Default.ManageAccounts, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Users", fontSize = 11.sp)
                                }
                            }
                            IconButton(onClick = onLogout, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Logout, "Logout", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pages.forEach { item ->
                        WebNavButton(
                            label = if (item == AppPage.RecordForm && state.selectedRecord != null) "Edit record" else item.label,
                            selected = state.page == item,
                            onClick = { onPage(item) }
                        )
                    }
                }
                HorizontalDivider(color = UlaLine)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorBanner(it, onDismissError) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.page) {
                    AppPage.Dashboard -> DashboardScreen(state, onRefreshRecords)
                    AppPage.Records -> RecordsScreen(
                        records = state.records,
                        canEdit = user.canEdit,
                        lastSync = state.lastSync,
                        onRefresh = onRefreshRecords,
                        onRecord = onRecord
                    )
                    AppPage.RecordForm -> RecordFormScreen(
                        record = state.selectedRecord,
                        canEdit = user.canEdit,
                        saving = state.saving,
                        lastSync = state.lastSync,
                        installers = state.installers,
                        onSync = onRefreshRecords,
                        onClear = { onPage(AppPage.RecordForm) },
                        onSave = onSave
                    )
                    AppPage.Reports -> ReportsScreen(state.records)
                    AppPage.Activity -> ActivityScreen(state.activity, onRefreshActivity)
                    AppPage.Access -> AccessScreen(
                        users = state.authorizedUsers,
                        installers = state.installers,
                        loading = state.loading,
                        onSaveUser = onSaveUser,
                        onSetUserActive = onSetUserActive,
                        onSetUserRole = onSetUserRole,
                        onRemoveUser = onRemoveUser,
                        onAddInstaller = onAddInstaller,
                        onRemoveInstaller = onRemoveInstaller
                    )
                }
            }
        }
    }

    if (showActivity && user.canAdmin) {
        ActivityDialog(
            activity = state.activity,
            loading = state.loading,
            onRefresh = onRefreshActivity,
            onDismiss = { showActivity = false }
        )
    }
}

@Composable
private fun WebNavButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(9.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
        ) { Text(label, fontSize = 12.sp) }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, UlaLine),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
        ) { Text(label, color = UlaNavy, fontSize = 12.sp) }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = Color(0xFFFFECE8), contentColor = UlaError) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ErrorOutline, null)
            Spacer(Modifier.width(9.dp))
            Text(message, modifier = Modifier.weight(1f), fontSize = 13.sp)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

private data class Metric(
    val title: String,
    val value: String,
    val icon: ImageVector,
    val color: Color
)

@Composable
private fun DashboardScreen(state: UlaAppState, onRefresh: () -> Unit) {
    val installed = state.records.count { it.installationDate.isNotBlank() }
    val paid = state.records.sumOf { numericAmount(it.values["paidAmount"]) }
    val received = state.records.sumOf { numericAmount(it.values["paymentReceivedFromConsumer"]) }
    val metrics = listOf(
        Metric("Total records", state.records.size.toString(), Icons.Default.Groups, UlaNavy),
        Metric("Total paid", moneyAmount(paid), Icons.Default.Payments, UlaBlue),
        Metric("Payment received", moneyAmount(received), Icons.Default.AccountBalanceWallet, UlaOrange),
        Metric("Installed", installed.toString(), Icons.Default.SolarPower, UlaGreen)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UlaNavy),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Welcome to", color = Color.White.copy(alpha = .78f))
                        Text("SunSwift ULA", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Registration, installation tracking and reports", color = UlaOrange, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(Icons.Default.SolarPower, null, tint = UlaOrange, modifier = Modifier.size(58.dp))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ULA overview", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = UlaNavy, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh records") }
            }
        }
        items(metrics.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { metric -> MetricCard(metric, Modifier.weight(1f)) }
            }
        }
        item {
            AssistChip(
                onClick = onRefresh,
                label = { Text("Cloud connected${if (state.lastSync.isNotBlank()) " • ${state.lastSync}" else ""}") },
                leadingIcon = { Icon(Icons.Default.CloudDone, null, tint = UlaGreen) }
            )
        }
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp)) {
            Icon(metric.icon, null, tint = metric.color, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(10.dp))
            Text(metric.value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = metric.color)
            Text(metric.title, fontSize = 13.sp, color = Color(0xFF52636E))
        }
    }
}

@Composable
private fun RecordsScreen(
    records: List<UlaRecord>,
    canEdit: Boolean,
    lastSync: String,
    onRefresh: () -> Unit,
    onRecord: (UlaRecord) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var village by remember { mutableStateOf("") }
    var panchayat by remember { mutableStateOf("") }
    var division by remember { mutableStateOf("") }
    val filtered = remember(records, search, village, panchayat, division) {
        records.filter { record ->
            val query = search.trim()
            (query.isBlank() || record.consumerName.contains(query, true) ||
                record.consumerNo.contains(query, true) || record.mobileNo.contains(query, true)) &&
                (village.isBlank() || record.villageName.contains(village.trim(), true)) &&
                (panchayat.isBlank() || record.panchayatName.contains(panchayat.trim(), true)) &&
                (division.isBlank() || record.divisionName.contains(division.trim(), true))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ULA records", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = UlaNavy)
                Text("${filtered.size} of ${records.size}${if (lastSync.isNotBlank()) " • synced $lastSync" else ""}", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh records") }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search name, consumer or mobile") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                IconButton(onClick = { showFilters = !showFilters }) {
                    Icon(Icons.Default.FilterList, "Village, panchayat and division filters")
                }
            },
            singleLine = true
        )
        if (showFilters) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactFilter("Village", village) { village = it }
                    CompactFilter("Panchayat", panchayat) { panchayat = it }
                    CompactFilter("Division", division) { division = it }
                    TextButton(onClick = { village = ""; panchayat = ""; division = "" }) {
                        Text("Clear filters")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (records.isEmpty()) "No cloud records found." else "No records match these filters.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(filtered, key = { it.id.ifBlank { it.consumerNo } }) { record ->
                    RecordCard(record, canEdit, onRecord)
                }
            }
        }
    }
}

@Composable
private fun CompactFilter(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun RecordCard(record: UlaRecord, canEdit: Boolean, onRecord: (UlaRecord) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onRecord(record) },
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, UlaLine),
        elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(record.consumerNo.ifBlank { "Consumer No —" }, fontWeight = FontWeight.Bold, color = UlaOrange, fontSize = 13.sp)
                Text(record.consumerName.ifBlank { "Unnamed consumer" }, fontWeight = FontWeight.Bold, color = UlaNavy)
                Text(
                    "Mobile: ${record.mobileNo.ifBlank { "—" }} • Ref: ${record.values["referenceNo"].orEmpty().ifBlank { "—" }}",
                    fontSize = 11.sp, color = UlaMuted
                )
                Text(
                    listOf(record.villageName, record.panchayatName, record.sectionName, record.divisionName)
                        .filter(String::isNotBlank).joinToString(" • ").ifBlank { "Location not recorded" },
                    fontSize = 11.sp,
                    color = UlaMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val equipment = listOf(
                    record.values["panelNo1"].orEmpty(),
                    record.values["panelNo2"].orEmpty(),
                    record.values["inverterSerialNo"].orEmpty()
                ).count(String::isNotBlank)
                Text(
                    "Installation: ${record.installationDate.ifBlank { "Pending" }} • Equipment: $equipment/3",
                    fontSize = 11.sp, color = if (record.installationDate.isBlank()) UlaOrange else UlaGreen
                )
                if (record.updatedBy.isNotBlank()) {
                    Text("Last modified by ${record.updatedBy}", fontSize = 10.sp, color = UlaMuted)
                }
            }
            Icon(
                if (canEdit) Icons.Default.Edit else Icons.Default.Visibility,
                if (canEdit) "Open for edit" else "View record",
                tint = UlaOrange,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun RecordFormScreen(
    record: UlaRecord?,
    canEdit: Boolean,
    saving: Boolean,
    lastSync: String,
    installers: List<String>,
    onSync: () -> Unit,
    onClear: () -> Unit,
    onSave: (Map<String, String>) -> Unit
) {
    val recordKey = record?.id ?: "new-record"
    val values = remember(recordKey) {
        mutableStateMapOf<String, String>().apply {
            ULA_EDIT_FIELDS.keys.forEach { key -> put(key, record?.values?.get(key).orEmpty()) }
        }
    }
    val editing = record != null
    var validationMessage by remember(recordKey) { mutableStateOf("") }

    fun clearForm() {
        if (editing) {
            onClear()
            return
        }
        ULA_EDIT_FIELDS.keys.forEach { values[it] = "" }
        validationMessage = ""
    }

    fun validateAndSave() {
        val mobile = values["mobileNo"].orEmpty()
        val consumer = values["consumerNo"].orEmpty()
        val reference = values["referenceNo"].orEmpty()
        validationMessage = when {
            !mobile.matches(Regex("\\d{10}")) -> "Mobile No must contain exactly 10 digits."
            !consumer.matches(Regex("[A-Za-z0-9]{12}")) -> "Electricity Consumer No must contain exactly 12 letters or numbers."
            !reference.matches(Regex("\\d{14}")) -> "Reference No must contain exactly 14 digits."
            values["consumerName"].orEmpty().isBlank() -> "Consumer Name is required."
            else -> ""
        }
        if (validationMessage.isBlank()) onSave(values.toMap())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                when {
                    editing && canEdit -> "Edit registration record"
                    editing -> "Registration record details"
                    else -> "New registration record"
                },
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = UlaNavy
            )
            Text("Registration, payment, installation and equipment details", color = UlaMuted, fontSize = 13.sp)
            record?.let { current ->
                Text("${current.consumerName} • ${current.consumerNo}", color = UlaOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            FormSection("Registration and consumer") {
                FormField(values, "registrationDate", canEdit, placeholder = "dd-mm-yyyy")
                FormField(values, "registerBy", canEdit)
                FormField(
                    values, "mobileNo", canEdit,
                    helper = "Required. Enter exactly 10 digits.",
                    keyboardType = KeyboardType.Phone, maxLength = 10, digitsOnly = true
                )
                FormField(
                    values, "consumerNo", canEdit,
                    helper = "Required and unique. Enter exactly 12 letters or numbers.", maxLength = 12
                )
                FormField(
                    values, "referenceNo", canEdit,
                    helper = "Required. Enter exactly 14 digits.",
                    keyboardType = KeyboardType.Number, maxLength = 14, digitsOnly = true
                )
                FormField(values, "consumerName", canEdit)
            }
        }
        item {
            FormSection("Consumer location") {
                FormField(values, "villageName", canEdit)
                FormField(values, "panchayatName", canEdit)
                FormField(values, "sectionName", canEdit)
                DivisionField(values["divisionName"].orEmpty(), canEdit) { values["divisionName"] = it }
            }
        }
        item {
            FormSection("Survey and payment") {
                FormField(values, "surveyDate", canEdit, placeholder = "dd-mm-yyyy")
                FormField(values, "paymentDate", canEdit, placeholder = "dd-mm-yyyy")
                FormField(values, "paidAmount", canEdit, keyboardType = KeyboardType.Decimal)
                FormField(values, "paymentReceivedBy", canEdit)
                FormField(values, "paymentReceiveDate", canEdit, placeholder = "dd-mm-yyyy")
                FormField(values, "paymentReceivedFromConsumer", canEdit, keyboardType = KeyboardType.Decimal)
                FormField(values, "consumerPaymentReceivedBy", canEdit)
            }
        }
        item {
            FormSection("Installation") {
                FormField(values, "installationDate", canEdit, placeholder = "dd-mm-yyyy")
                if (installers.isEmpty()) {
                    FormField(values, "installer1", canEdit)
                    FormField(values, "installer2", canEdit)
                    FormField(values, "installer3", canEdit)
                } else {
                    InstallerField("Installer1", values["installer1"].orEmpty(), installers, canEdit) { values["installer1"] = it }
                    InstallerField("Installer2", values["installer2"].orEmpty(), installers, canEdit) { values["installer2"] = it }
                    InstallerField("Installer3", values["installer3"].orEmpty(), installers, canEdit) { values["installer3"] = it }
                }
            }
        }
        item {
            FormSection("Equipment and distance") {
                val uniqueHelper = "If entered, this number must not be used by another consumer."
                FormField(values, "panelNo1", canEdit, helper = uniqueHelper)
                FormField(values, "panelNo2", canEdit, helper = uniqueHelper)
                FormField(values, "inverterSerialNo", canEdit, helper = uniqueHelper)
                FormField(values, "distanceTpsodlToSectionKm", canEdit, keyboardType = KeyboardType.Decimal)
                FormField(values, "distanceSectionToConsumerKm", canEdit, keyboardType = KeyboardType.Decimal)
            }
        }
        item {
            Surface(
                color = Color(0xFFF2FBF6),
                shape = RoundedCornerShape(11.dp),
                border = BorderStroke(1.dp, Color(0xFFB9DDC7))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Google Cloud database", color = Color(0xFF155B37), fontWeight = FontWeight.Bold)
                    Text(
                        "Every authorized device uses the same live register. Each Consumer No has one row; later saves update that same row.",
                        color = Color(0xFF315B47), fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDone, null, tint = UlaGreen, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Cloud connected${if (lastSync.isNotBlank()) " • $lastSync" else ""}", color = UlaGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onSync, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("Sync now", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item {
            Surface(
                color = Color(0xFFFFF5F2),
                shape = RoundedCornerShape(11.dp),
                border = BorderStroke(1.dp, Color(0xFFFFC9BC))
            ) {
                Text(
                    "Consent PDF fields: Consumer Name, Electricity Consumer No and Mobile No are inserted directly. Address is assembled from Village, Panchayat, Section and Division.",
                    modifier = Modifier.padding(14.dp), color = UlaError, fontSize = 12.sp
                )
            }
        }
        if (validationMessage.isNotBlank()) {
            item { ErrorBanner(validationMessage) { validationMessage = "" } }
        }
        if (canEdit) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::validateAndSave,
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(9.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (editing) "Update record" else "Save record")
                    }
                    OutlinedButton(
                        onClick = ::clearForm,
                        enabled = !saving,
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, UlaLine)
                    ) { Text("Clear form") }
                }
            }
        }
        record?.let { current ->
            item {
                Text(
                    "Last modified by ${current.updatedBy.ifBlank { "—" }}${if (current.updatedAt.isNotBlank()) " • ${prettyTimestamp(current.updatedAt)}" else ""}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun FormSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, UlaLine),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = UlaNavy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = UlaLine)
            content()
        }
    }
}

@Composable
private fun FormField(
    values: MutableMap<String, String>,
    key: String,
    enabled: Boolean,
    helper: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int = Int.MAX_VALUE,
    digitsOnly: Boolean = false,
    placeholder: String = ""
) {
    val value = values[key].orEmpty()
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            values[key] = (if (digitsOnly) input.filter(Char::isDigit) else input).take(maxLength)
        },
        label = { Text(ULA_EDIT_FIELDS[key].orEmpty()) },
        placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder) }),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        supportingText = if (helper.isBlank()) null else ({ Text(helper, fontSize = 11.sp, color = UlaMuted) }),
        shape = RoundedCornerShape(9.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DivisionField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val divisions = listOf("JED Jeypore", "KED Koraput", "MED Malkangiri", "RED Rayagada", "NED Nabarangpur")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Division Name") },
            placeholder = { Text("Select Division") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            shape = RoundedCornerShape(9.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            divisions.forEach { division ->
                DropdownMenuItem(
                    text = { Text(division) },
                    onClick = {
                        onChange(division)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallerField(
    label: String,
    value: String,
    installers: List<String>,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text("Select installer") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            shape = RoundedCornerShape(9.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Not selected") },
                onClick = { onChange(""); expanded = false }
            )
            installers.forEach { installer ->
                DropdownMenuItem(
                    text = { Text(installer) },
                    onClick = { onChange(installer); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun AccessScreen(
    users: List<UlaAuthorizedUser>,
    installers: List<String>,
    loading: Boolean,
    onSaveUser: (String, String, String) -> Unit,
    onSetUserActive: (String, Boolean) -> Unit,
    onSetUserRole: (String, String) -> Unit,
    onRemoveUser: (String) -> Unit,
    onAddInstaller: (String) -> Unit,
    onRemoveInstaller: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("EDITOR") }
    var installerName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("User control", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = UlaNavy)
            Text(
                "Only active users listed here can request an OTP and open ULA.",
                color = UlaMuted,
                fontSize = 12.sp
            )
        }
        item {
            FormSection("Authorize a user") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mobile,
                    onValueChange = { mobile = it.filter(Char::isDigit).take(10) },
                    label = { Text("Mobile") },
                    leadingIcon = { Text("+91") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                UserRoleField(role, true) { role = it }
                Button(
                    onClick = {
                        onSaveUser(name, mobile, role)
                        name = ""
                        mobile = ""
                    },
                    enabled = !loading && name.isNotBlank() && mobile.length == 10,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PersonAdd, null)
                    Spacer(Modifier.width(7.dp))
                    Text("Save authorized user")
                }
            }
        }
        item {
            Text("Authorized users (${users.size})", fontWeight = FontWeight.Bold, color = UlaNavy, fontSize = 18.sp)
        }
        items(users, key = { it.mobile }) { user ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, UlaLine),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(user.name.ifBlank { user.mobile }, fontWeight = FontWeight.Bold, color = UlaNavy)
                            Text(user.mobile, color = UlaMuted, fontSize = 12.sp)
                            if (user.lastLoginAt.isNotBlank()) {
                                Text("Last login ${prettyTimestamp(user.lastLoginAt)}", color = UlaMuted, fontSize = 10.sp)
                            }
                        }
                        if (user.permanent) {
                            AssistChip(onClick = {}, label = { Text("Permanent Admin") })
                        } else {
                            Switch(
                                checked = user.active,
                                onCheckedChange = { onSetUserActive(user.mobile, it) },
                                enabled = !loading
                            )
                        }
                    }
                    if (!user.permanent) {
                        UserRoleField(user.role, !loading) { onSetUserRole(user.mobile, it) }
                        TextButton(
                            onClick = { onRemoveUser(user.mobile) },
                            enabled = !loading,
                            colors = ButtonDefaults.textButtonColors(contentColor = UlaError)
                        ) {
                            Icon(Icons.Default.PersonRemove, null)
                            Spacer(Modifier.width(5.dp))
                            Text("Remove access")
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("Installer names", fontWeight = FontWeight.Bold, color = UlaNavy, fontSize = 18.sp)
            Text("Used by the Installer1, Installer2 and Installer3 dropdowns.", color = UlaMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = installerName,
                    onValueChange = { installerName = it.take(80) },
                    label = { Text("Installer name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onAddInstaller(installerName); installerName = "" },
                    enabled = !loading && installerName.isNotBlank()
                ) { Text("Add") }
            }
        }
        items(installers, key = { it }) { installer ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, UlaLine),
                color = Color.White
            ) {
                Row(Modifier.padding(start = 13.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(installer, modifier = Modifier.weight(1f), color = UlaInk)
                    IconButton(onClick = { onRemoveInstaller(installer) }, enabled = !loading) {
                        Icon(Icons.Default.Close, "Remove installer", tint = UlaError)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserRoleField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Role") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("EDITOR", "VIEWER").forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onChange(option); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun ReportsScreen(records: List<UlaRecord>) {
    var groupField by remember { mutableStateOf("divisionName") }
    val months = remember(records) {
        records.flatMap { record ->
            listOf(dateMonth(record.values["registrationDate"]), dateMonth(record.installationDate))
        }.filter(String::isNotBlank).distinct().sortedDescending()
    }
    var selectedMonth by remember { mutableStateOf("") }
    LaunchedEffect(months) {
        if (selectedMonth !in months) selectedMonth = months.firstOrNull().orEmpty()
    }
    val grouped = remember(records, groupField, selectedMonth) {
        records.groupBy { it.values[groupField].orEmpty().trim().ifBlank { "Not specified" } }
            .map { (name, rows) ->
                AnalyticsRow(
                    name = name,
                    monthRegistrations = rows.count { dateMonth(it.values["registrationDate"]) == selectedMonth },
                    monthInstallations = rows.count { dateMonth(it.installationDate) == selectedMonth },
                    totalRegistrations = rows.size,
                    totalInstallations = rows.count { it.installationDate.isNotBlank() }
                )
            }.sortedWith(compareByDescending<AnalyticsRow> { it.totalRegistrations }.thenBy { it.name })
    }
    val monthRegistrations = grouped.sumOf { it.monthRegistrations }
    val monthInstallations = grouped.sumOf { it.monthInstallations }
    val totalInstallations = grouped.sumOf { it.totalInstallations }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Registration and installation analytics", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = UlaNavy)
            Text("Compare the selected month with the complete live register.", color = Color.Gray, fontSize = 12.sp)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Division" to "divisionName",
                    "Panchayat" to "panchayatName",
                    "Village" to "villageName"
                ).forEach { (label, value) ->
                    FilterChip(
                        selected = groupField == value,
                        onClick = { groupField = value },
                        label = { Text(label, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (months.isNotEmpty()) {
            item {
                SimpleSelect(
                    label = "Select month",
                    value = selectedMonth,
                    options = months,
                    display = ::monthLabel,
                    onChange = { selectedMonth = it }
                )
            }
        }
        item {
            Card(border = BorderStroke(1.dp, UlaLine), elevation = CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(14.dp)) {
                    SummaryRow("Registrations in ${monthLabel(selectedMonth)}", monthRegistrations.toString(), UlaOrange)
                    SummaryRow("Installations in ${monthLabel(selectedMonth)}", monthInstallations.toString(), UlaGreen)
                    SummaryRow("Total registrations", records.size.toString(), UlaNavy)
                    SummaryRow("Total installations", totalInstallations.toString(), UlaBlue)
                }
            }
        }
        items(grouped, key = { it.name }) { row ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, UlaLine),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(row.name, fontWeight = FontWeight.Bold, color = UlaNavy)
                    Text(
                        "Month: ${row.monthRegistrations} registered • ${row.monthInstallations} installed",
                        color = UlaOrange,
                        fontSize = 12.sp
                    )
                    Text(
                        "All time: ${row.totalRegistrations} registered • ${row.totalInstallations} installed",
                        color = UlaMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

private data class AnalyticsRow(
    val name: String,
    val monthRegistrations: Int,
    val monthInstallations: Int,
    val totalRegistrations: Int,
    val totalInstallations: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSelect(
    label: String,
    value: String,
    options: List<String>,
    display: (String) -> String = { it },
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = display(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = { onChange(option); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 20.sp)
    }
}

@Composable
private fun ActivityDialog(
    activity: List<UlaActivity>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            shape = RoundedCornerShape(14.dp),
            color = UlaBackground
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(UlaNavy).padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("User activity", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Admin-only permanent Audit Log", color = Color.White.copy(alpha = .72f), fontSize = 11.sp)
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh activity", tint = Color.White) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close user activity", tint = Color.White) }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "${activity.size} recent events • modified fields show before → after values",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = UlaMuted, fontSize = 12.sp
                )
                if (activity.isEmpty() && !loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No activity found.", color = UlaMuted)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 14.dp)
                    ) {
                        items(activity) { item -> ActivityCard(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(item: UlaActivity) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, UlaLine),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.action, fontWeight = FontWeight.Bold, color = activityColor(item.action))
                Spacer(Modifier.weight(1f))
                Text(prettyTimestamp(item.timestamp), color = UlaMuted, fontSize = 10.sp)
            }
            Text(item.consumerName.ifBlank { "System activity" }, fontWeight = FontWeight.SemiBold, color = UlaInk)
            if (item.consumerNo.isNotBlank()) Text("Consumer ${item.consumerNo}", fontSize = 12.sp, color = UlaMuted)
            Text("By ${item.user.ifBlank { "Unknown user" }}", fontSize = 12.sp, color = UlaNavy)
            if (item.comments.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = UlaLine)
                Text(item.comments, fontSize = 12.sp, color = Color(0xFF44515A))
            }
        }
    }
}

@Composable
private fun ActivityScreen(activity: List<UlaActivity>, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("User activity", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = UlaNavy)
                Text("Admin-only audit trail • ${activity.size} recent events", color = Color.Gray, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh activity") }
        }
        Spacer(Modifier.height(8.dp))
        if (activity.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No activity found.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(activity) { item ->
                    ActivityCard(item)
                }
            }
        }
    }
}

private fun activityColor(action: String): Color = when (action.uppercase()) {
    "CREATE", "LOGIN" -> UlaGreen
    "UPDATE" -> UlaBlue
    "DELETE", "LOGOUT" -> UlaOrange
    else -> UlaNavy
}

private fun prettyTimestamp(raw: String): String {
    if (raw.isBlank()) return ""
    return raw.replace('T', ' ').replace(Regex("\\.\\d{3}Z$"), " UTC")
}

private fun numericAmount(raw: String?): Double = raw.orEmpty()
    .replace(Regex("[^0-9.-]"), "")
    .toDoubleOrNull() ?: 0.0

private fun moneyAmount(value: Double): String = "₹%,.0f".format(value)

private fun dateMonth(raw: String?): String {
    val value = raw.orEmpty().trim()
    Regex("^(\\d{4})[-/](\\d{2})[-/]\\d{2}").find(value)?.let {
        return "${it.groupValues[1]}-${it.groupValues[2]}"
    }
    Regex("^\\d{2}[-/](\\d{2})[-/](\\d{4})").find(value)?.let {
        return "${it.groupValues[2]}-${it.groupValues[1]}"
    }
    return ""
}

private fun monthLabel(value: String): String {
    val match = Regex("^(\\d{4})-(\\d{2})$").find(value) ?: return "selected month"
    val month = match.groupValues[2].toIntOrNull() ?: return value
    val names = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
    return names.getOrNull(month - 1)?.let { "$it ${match.groupValues[1]}" } ?: value
}
