package app.player.feature.library.smb

import app.player.feature.library.R
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why a share could not be opened, in the viewer's terms. Never the library's own text. */
enum class SmbFailure(val message: Int) {
    Login(R.string.smb_err_login),
    NoShare(R.string.smb_err_share),
    Denied(R.string.smb_err_denied),
    Unreachable(R.string.smb_err_unreachable),
    Other(R.string.smb_err_other),
}

/** Walks the cause chain: smbj wraps socket errors, and names SMB failures by NT status in the message. */
fun smbFailure(error: Throwable?): SmbFailure {
    var t = error
    while (t != null) {
        val text = t.message.orEmpty()
        when {
            "STATUS_LOGON_FAILURE" in text || "STATUS_WRONG_PASSWORD" in text ||
                "STATUS_ACCOUNT_DISABLED" in text -> return SmbFailure.Login
            "STATUS_BAD_NETWORK_NAME" in text -> return SmbFailure.NoShare
            "STATUS_ACCESS_DENIED" in text -> return SmbFailure.Denied
            t is ConnectException || t is UnknownHostException || t is SocketTimeoutException ||
                t is NoRouteToHostException -> return SmbFailure.Unreachable
        }
        t = t.cause?.takeIf { it !== t }
    }
    return SmbFailure.Other
}
