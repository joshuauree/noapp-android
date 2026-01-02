/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.importer

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.di.ActiveSessionHolder
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class ImporterService : Service() {
    private companion object {
        /**
         * Command to the service to get the data.
         */
        const val MSG_GET_DATA = 1

        const val KEY_ERROR_STR = "error"
        const val KEY_USER_ID_STR = "userId"
        const val KEY_SECRETS_STR = "secrets"
    }

    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    private val signaturePermissionChecker = SignaturePermissionChecker()

    /**
     * Handler of incoming messages from clients.
     */
    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            Timber.w("ImporterService: handling message ${msg.what}")
            val replyTo = msg.replyTo
            if (replyTo == null) {
                Timber.e("ImporterService: no replyTo in the message, cannot answer")
            } else {
                val bundle = Bundle()
                if (signaturePermissionChecker.check(msg.sendingUid, packageManager)) {
                    Timber.w("ImporterService: Authorized caller")
                    when (msg.what) {
                        MSG_GET_DATA -> bundle.putSessionData()
                        else -> bundle.putString(KEY_ERROR_STR, "Unknown command ${msg.what}")
                    }
                } else {
                    Timber.w("ImporterService: Unauthorized caller")
                    bundle.putString(KEY_ERROR_STR, "Unauthorized")
                }
                replyTo.sendResponse(msg.what, bundle)
            }
        }
    }

    private fun Bundle.putSessionData() {
        val session = activeSessionHolder.getSafeActiveSession()
        if (session == null) {
            // Keep an empty Bundle to indicate that there is no session
        } else {
            putString(KEY_USER_ID_STR, session.myUserId)
            val secret = session.cryptoService().exportSecrets()
            secret.fold(
                    onSuccess = {
                        Timber.d("ImporterService: Retrieved secrets from session successfully")
                        putString(KEY_SECRETS_STR, it)
                    },
                    onFailure = {
                        Timber.w(it, "ImporterService: Failed to retrieve secrets from session")
                    }
            )
        }
    }

    private fun Messenger.sendResponse(what: Int, bundle: Bundle) {
        Timber.d("ImporterService: send response to client")
        try {
            val message = Message.obtain(null, what).also {
                it.data = bundle
            }
            send(message)
        } catch (e: RemoteException) {
            // The client is dead.
            Timber.e(e, "ImporterService: The client is dead.")
        }
    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    override fun onBind(intent: Intent): IBinder? {
        Timber.w("ImporterService: onBind")
        val messenger = Messenger(IncomingHandler())
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.w("ImporterService: onUnbind")
        return super.onUnbind(intent)
    }
}
