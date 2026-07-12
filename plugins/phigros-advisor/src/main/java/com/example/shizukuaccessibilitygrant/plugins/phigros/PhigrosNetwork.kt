package com.example.shizukuaccessibilitygrant.plugins.phigros

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object PhigrosHttp {
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        String(request("GET", url, headers), Charsets.UTF_8)

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        request("GET", url, headers)

    fun postForm(url: String, form: Map<String, String>, acceptErrorResponse: Boolean = false): String {
        val body = form.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        return String(
            request("POST", url, mapOf("Content-Type" to "application/x-www-form-urlencoded"), body, acceptErrorResponse),
            Charsets.UTF_8,
        )
    }

    fun postJson(url: String, json: JSONObject, headers: Map<String, String>): String = String(
        request(
            "POST",
            url,
            headers + mapOf("Content-Type" to "application/json"),
            json.toString().toByteArray(Charsets.UTF_8),
        ),
        Charsets.UTF_8,
    )

    private fun request(
        method: String,
        rawUrl: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        acceptErrorResponse: Boolean = false,
    ): ByteArray {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, */*")
            setRequestProperty("User-Agent", "Android-Tool-Suite/Phigros-Data-Studio")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = input?.use { stream ->
                val output = ByteArrayOutputStream()
                stream.copyTo(output)
                output.toByteArray()
            } ?: ByteArray(0)
            if (code !in 200..299 && !acceptErrorResponse) {
                val detail = String(bytes, Charsets.UTF_8).take(500)
                throw IOException("HTTP $code：${detail.ifBlank { connection.responseMessage }}")
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }
}

internal class PhigrosCloudClient {
    fun requestTapLogin(server: PhigrosServer): TapLoginRequest {
        val config = ServerConfig.of(server)
        val deviceId = UUID.randomUUID().toString().replace("-", "")
        val root = JSONObject(
            PhigrosHttp.postForm(
                config.tapAccounts + "/oauth2/v1/device/code",
                linkedMapOf(
                    "client_id" to TAP_CLIENT_ID,
                    "response_type" to "device_code",
                    "scope" to "public_profile",
                    "version" to "2.1",
                    "platform" to "unity",
                    "info" to JSONObject().put("device_id", deviceId).toString(),
                ),
            ),
        )
        val data = root.optJSONObject("data") ?: root
        val code = data.optString("device_code")
        val url = data.optString("qrcode_url")
        require(code.isNotBlank() && url.isNotBlank()) { "TapTap 未返回登录二维码" }
        val expires = data.optInt("expires_in", 300).coerceAtLeast(30)
        return TapLoginRequest(
            server = server,
            deviceId = deviceId,
            deviceCode = code,
            loginUrl = url,
            expiresAt = System.currentTimeMillis() + expires * 1000L,
            intervalSeconds = data.optInt("interval", 2).coerceIn(1, 10),
        )
    }

    fun pollTapLogin(request: TapLoginRequest): String? {
        val config = ServerConfig.of(request.server)
        val root = JSONObject(
            PhigrosHttp.postForm(
                config.tapAccounts + "/oauth2/v1/token",
                linkedMapOf(
                    "grant_type" to "device_token",
                    "client_id" to TAP_CLIENT_ID,
                    "secret_type" to "hmac-sha-1",
                    "code" to request.deviceCode,
                    "version" to "1.0",
                    "platform" to "unity",
                    "info" to JSONObject().put("device_id", request.deviceId).toString(),
                ),
                acceptErrorResponse = true,
            ),
        )
        val tokenData = root.optJSONObject("data") ?: root
        if (!tokenData.has("mac_key") || !tokenData.has("kid")) {
            val error = tokenData.optString("error", root.optString("error"))
            if (error in setOf("authorization_pending", "authorization_waiting", "slow_down", "")) return null
            throw IOException("TapTap 登录失败：$error")
        }
        val profileUrl = config.tapOpen + "/account/profile/v1?client_id=$TAP_CLIENT_ID"
        val profileRoot = JSONObject(
            PhigrosHttp.getText(
                profileUrl,
                mapOf("Authorization" to macAuthorization(profileUrl, tokenData)),
            ),
        )
        val profileData = profileRoot.optJSONObject("data") ?: profileRoot
        val auth = JSONObject()
        copyJson(profileData, auth)
        copyJson(tokenData, auth)
        val now = System.currentTimeMillis() / 1000L
        val sign = md5("$now${config.appKey}") + ",$now"
        val body = JSONObject().put("authData", JSONObject().put("taptap", auth))
        val login = JSONObject(
            PhigrosHttp.postJson(
                config.leanBase + "/users",
                body,
                mapOf("X-LC-Id" to config.appId, "X-LC-Sign" to sign),
            ),
        )
        return login.optString("sessionToken").takeIf { SecureTokenStore.TOKEN_PATTERN.matches(it) }
            ?: throw IOException("LeanCloud 未返回 SessionToken")
    }

    fun fetchSave(token: String, server: PhigrosServer, catalog: List<SongInfo>): PhigrosSave {
        require(SecureTokenStore.TOKEN_PATTERN.matches(token)) { "SessionToken 格式错误" }
        val config = ServerConfig.of(server)
        val headers = mapOf(
            "X-LC-Id" to config.appId,
            "X-LC-Key" to config.appKey,
            "X-LC-Session" to token,
            "User-Agent" to "LeanCloud-CSharp-SDK/1.0.3",
        )
        val user = JSONObject(PhigrosHttp.getText(config.leanBase + "/users/me", headers))
        val objectId = user.optString("objectId")
        require(objectId.isNotBlank()) { "SessionToken 无法读取玩家信息" }
        val where = JSONObject().put(
            "user",
            JSONObject().put("__type", "Pointer").put("className", "_User").put("objectId", objectId),
        )
        val query = config.leanBase + "/gamesaves/?skip=0&limit=100&where=" +
            URLEncoder.encode(where.toString(), "UTF-8") + "&include=cover,gameFile"
        val saves = JSONObject(PhigrosHttp.getText(query, headers)).optJSONArray("results") ?: JSONArray()
        require(saves.length() > 0) { "没有找到云存档，请先在游戏中上传存档" }
        val newest = (0 until saves.length())
            .mapNotNull(saves::optJSONObject)
            .filter { it.optJSONObject("gameFile")?.optString("url").isNullOrBlank().not() }
            .maxByOrNull { modifiedAt(it) }
            ?: throw IOException("云存档缺少可下载的 gameFile")
        val fileUrl = newest.getJSONObject("gameFile").getString("url")
        val zip = PhigrosHttp.getBytes(fileUrl)
        return PhigrosSaveParser(catalog).parse(
            zipBytes = zip,
            playerInfo = user,
            saveInfo = newest,
        )
    }

    private fun modifiedAt(save: JSONObject): String =
        save.optJSONObject("modifiedAt")?.optString("iso")
            ?: save.optString("updatedAt", save.optString("createdAt", ""))

    private fun macAuthorization(url: String, token: JSONObject): String {
        val parsed = URL(url)
        val timestamp = String.format(Locale.ROOT, "%010d", System.currentTimeMillis() / 1000L)
        val nonce = Base64.encodeToString(ByteArray(16).also(SecureRandom()::nextBytes), Base64.NO_WRAP)
        val uri = parsed.path + (parsed.query?.let { "?$it" } ?: "")
        val port = if (parsed.port > 0) parsed.port else 443
        val data = "$timestamp\n$nonce\nGET\n$uri\n${parsed.host}\n$port\n\n"
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(token.getString("mac_key").toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        }
        val signature = Base64.encodeToString(mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "MAC id=\"${token.getString("kid")}\", ts=\"$timestamp\", nonce=\"$nonce\", mac=\"$signature\""
    }

    private fun copyJson(from: JSONObject, to: JSONObject) {
        val names = from.names() ?: return
        for (index in 0 until names.length()) {
            val name = names.optString(index)
            to.put(name, from.opt(name))
        }
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class ServerConfig(
        val appId: String,
        val appKey: String,
        val leanBase: String,
        val tapAccounts: String,
        val tapOpen: String,
    ) {
        companion object {
            fun of(server: PhigrosServer): ServerConfig = when (server) {
                PhigrosServer.CN -> ServerConfig(
                    "rAK3FfdieFob2Nn8Am",
                    "Qr9AEqtuoSVS3zeD6iVbM4ZC0AtkJcQ89tywVyi0",
                    "https://rak3ffdi.cloud.tds1.tapapis.cn/1.1",
                    "https://accounts.tapapis.cn",
                    "https://open.tapapis.cn",
                )
                PhigrosServer.GLOBAL -> ServerConfig(
                    "kviehleldgxsagpozb",
                    "tG9CTm0LDD736k9HMM9lBZrbeBGRmUkjSfNLDNib",
                    "https://kviehlel.cloud.ap-sg.tapapis.com/1.1",
                    "https://accounts.tapapis.com",
                    "https://open.tapapis.com",
                )
            }
        }
    }

    companion object {
        private const val TAP_CLIENT_ID = "rAK3FfdieFob2Nn8Am"
    }
}
