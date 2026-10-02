package com.telefam.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.*
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real "Sign in with Apple" via ASAuthorizationAppleIDProvider — the actual system
 * flow, not a stub. Returns the real base64/UTF8-decoded identityToken JWT, which
 * the client sends to POST /api/auth/apple. The backend independently re-verifies
 * it against Apple's live JWKS (see AppleAuthVerifier.kt), so nothing here needs to
 * be trusted client-side alone.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleAuthLauncher {

    suspend fun signIn(): String = suspendCancellableCoroutine { cont ->
        val provider = ASAuthorizationAppleIDProvider()
        val request = provider.createRequest()
        request.requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)

        val controller = ASAuthorizationController(authorizationRequests = listOf(request))

        val delegate = object : NSObject(), ASAuthorizationControllerDelegateProtocol, ASAuthorizationControllerPresentationContextProvidingProtocol {
            override fun authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization: ASAuthorization) {
                val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
                val tokenData = credential?.identityToken
                if (tokenData == null) {
                    cont.resumeWithException(IllegalStateException("No identity token returned"))
                    return
                }
                val tokenString = NSString.create(data = tokenData, encoding = platform.Foundation.NSUTF8StringEncoding) as String?
                if (tokenString == null) {
                    cont.resumeWithException(IllegalStateException("Could not decode identity token"))
                } else {
                    cont.resume(tokenString)
                }
            }

            override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: platform.Foundation.NSError) {
                cont.resumeWithException(IllegalStateException(didCompleteWithError.localizedDescription))
            }

            override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): platform.UIKit.UIWindow {
                return UIApplication.sharedApplication.keyWindow ?: platform.UIKit.UIWindow()
            }
        }

        controller.delegate = delegate
        controller.presentationContextProvider = delegate
        controller.performRequests()
    }
}
