package com.telefam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telefam.shared.generated.resources.*
import com.telefam.ui.components.*
import com.telefam.ui.theme.TelefamColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun SignUpScreen(
    onSignUpClick: (email: String, password: String) -> Unit,
    onGoogleClick: () -> Unit,
    onAppleClick: () -> Unit,
    onLoginClick: () -> Unit,
    onForgotPasswordClick: (email: String) -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    showAppleButton: Boolean = false, // true on iOS builds
    /** Signed-out device pairing: scan the QR shown on the primary device. */
    onLinkDeviceClick: () -> Unit = {}
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(TelefamColors.White)) {

        Box(
            Modifier.fillMaxWidth().height(230.dp)
                .clip(TopWaveShape())
                .background(TelefamColors.BackgroundWash)
        )

        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(TelefamColors.PrimaryRed, TelefamColors.PrimaryRedDark))),
                contentAlignment = Alignment.Center
            ) {
                TelefamLogo(size = 44.dp, withCircle = false, circleColor = TelefamColors.White)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.app_name), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TelefamColors.PrimaryRed)
            Text(stringResource(Res.string.auth_tagline), color = TelefamColors.TextMuted)

            Spacer(Modifier.height(24.dp))

            Card(
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(stringResource(Res.string.signup_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TelefamColors.PrimaryRed,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(stringResource(Res.string.signup_subtitle), color = TelefamColors.TextMuted,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)

                    Spacer(Modifier.height(20.dp))
                    TelefamTextField(
                        value = email, onValueChange = { email = it },
                        label = stringResource(Res.string.email_label), placeholder = stringResource(Res.string.email_placeholder), leadingIcon = Icons.Filled.Email
                    )
                    Spacer(Modifier.height(16.dp))
                    TelefamTextField(
                        value = password, onValueChange = { password = it },
                        label = stringResource(Res.string.password_label), placeholder = stringResource(Res.string.password_placeholder), leadingIcon = Icons.Filled.Lock,
                        isPassword = true, passwordVisible = passwordVisible,
                        onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                        trailingIcon = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    )

                    if (errorMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(errorMessage, color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(20.dp))
                    TelefamPrimaryButton(
                        text = stringResource(Res.string.signup_title),
                        onClick = { onSignUpClick(email, password) },
                        loading = isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Divider(Modifier.weight(1f))
                        Text("  ${stringResource(Res.string.or_divider)}  ", color = TelefamColors.TextMuted)
                        Divider(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))

                    GoogleSignInButton(onClick = onGoogleClick)
                    if (showAppleButton) {
                        Spacer(Modifier.height(12.dp))
                        AppleSignInButton(onClick = onAppleClick)
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Text("${stringResource(Res.string.already_have_account)} ", color = TelefamColors.TextMuted)
                        Text(
                            stringResource(Res.string.login_title), color = TelefamColors.PrimaryRed, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onLoginClick)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onLinkDeviceClick)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null,
                            tint = TelefamColors.TextMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.login_link_device), color = TelefamColors.TextMuted,
                            fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }

        Box(
            Modifier.fillMaxWidth().height(140.dp).align(Alignment.BottomCenter)
                .clip(BottomWaveShape())
                .background(TelefamColors.PrimaryRed),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                stringResource(Res.string.forgot_password),
                color = TelefamColors.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 32.dp).clickable { onForgotPasswordClick(email.trim()) }
            )
        }
    }
}
