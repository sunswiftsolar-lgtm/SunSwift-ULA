package com.sunswift.ula

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal const val ULA_PRIVACY_POLICY_URL = "https://ula-dda9e-privacy.web.app/"
internal const val ULA_PHONE_PRIVACY_DISCLOSURE =
    "Your mobile number is sent to Google Firebase for OTP verification. " +
    "Google also stores and uses phone numbers for spam and abuse prevention across its services. " +
    "ULA uses your information as described in the Privacy Policy."

/** Public policy opens outside the origin-restricted, authenticated WebView. */
@Composable
internal fun PrivacyPolicyLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(ULA_PRIVACY_POLICY_URL))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "A browser is needed to open the Privacy Policy.", Toast.LENGTH_LONG).show()
            }
        },
        modifier = modifier
    ) {
        Spacer(Modifier.width(2.dp))
        Text("Privacy Policy")
    }
}
