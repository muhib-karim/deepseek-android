package app.dsandroid.core.llm

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

class ProviderHttpException(val status: Int, body: String) : IOException(ErrorExplainer.http(status, body))

object ErrorExplainer {
    fun http(status: Int, body: String = ""): String {
        val normalized = body.lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
        return when {
            status == 401 -> "Sign-in expired or key invalid. Sign in again or check your API key."
            status == 402 || Regex("insufficient.{0,40}(balance|quota)|(balance|quota).{0,40}(insufficient|exceeded|exhausted|not enough)|no remaining.{0,20}(balance|quota)").containsMatchIn(normalized) ->
                "This account has no remaining DeepSeek balance or free quota. Add a DeepSeek API key in Settings, or top up on the DeepSeek platform."
            status == 403 -> "DeepSeek refused this request for this account (403). This can happen when the account has no access or quota for this app. Try an API key in Settings."
            status == 429 -> "Too many requests. Please wait a moment, then retry your message."
            status in 500..599 -> "The service is busy or temporarily unavailable. Please retry in a moment."
            else -> "The provider could not accept this request ($status). Check the model and endpoint in Settings, then retry."
        }
    }
    fun explain(error: Throwable): String = when (error) {
        is ProviderHttpException -> error.message.orEmpty()
        is SocketTimeoutException -> "The request timed out. Check your connection and retry."
        is IOException -> "The connection was interrupted or the reply was incomplete. Check your internet connection and retry."
        else -> "The reply could not be processed. Check the model and endpoint in Settings, then retry."
    }
}
