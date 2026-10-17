package app.dsandroid.core.llm

interface AuthProvider { fun headers(): Map<String, String> }

private fun validateCredential(value: String, allowEmpty: Boolean = false) {
    require((allowEmpty || value.isNotBlank()) && value.all { it.code in 32..126 }) { "Invalid credential" }
}

class ApiKeyAuth(private val key: String) : AuthProvider {
    init { validateCredential(key) }
    override fun headers() = mapOf("Authorization" to "Bearer $key")
}

class DeepSeekAccountAuth(private val token: String) : AuthProvider {
    init { validateCredential(token) }
    override fun headers() = mapOf("x-dsh-auth-token" to token)
}

class CustomEndpointAuth(private val key: String) : AuthProvider {
    init { validateCredential(key, allowEmpty = true) }
    override fun headers() = if (key.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $key")
}
