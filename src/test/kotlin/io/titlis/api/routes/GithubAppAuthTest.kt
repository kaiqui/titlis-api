package io.titlis.api.routes

import io.titlis.api.repository.TenantAiConfigRecord
import kotlinx.coroutines.runBlocking
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Chaves RSA fictícias geradas exclusivamente para teste (descartáveis, sem valor de produção).
private val PKCS1_PEM = """
-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEA51ygE9LROj2+tFRHE8Tdc5AnWgmh5T/E20ZMEOmN/2N0ztxL
lJ3XHkikiATAq+bB6kVvRA9mtJJyFmO8q65AGkPDHSWCQFuBk2MMY941JKdOqPwN
Lg3Q8s5YePgOz9ASnbbdyzIDYpAEoQQ/IhOF5TyKDTjtJP08iMcCENkWX9u5JpYr
KPCzWiixPLzFEf1/VuwDLz42h8w10Ls4aftsMBY6CilGXm+JczR+xtnUg/YmtuiB
FiJ6NwLqnyD73CHpBu1M5oPakkEfhCwdU+CMCckg4Hd3IvVGWTdZqyc/mg5elZML
r+CtbYxxXOC3ggHr0DQ0Byo+a0tnw5myxQhA8QIDAQABAoIBAB+SLSnnV7O53z8e
TQCumYV+g1AvBk+RLxy48AJSzJSwDq13tDyYeVVhlkoVU/FhMM72IKPJ8jrrzPOv
Mny+73JmEsb0aly73+er2XH7TYct6Xb3xhilvACWFc6UEvlarF+nJ4bl0T5u5qLO
QIs23MuWQkRho0Kr58nGVkrURNBZmeUcrR8Fn17WEfcHOI339Ta4Y8G8EfPa7aiW
VzRcie/BWbYflXGadaRZtrwZtsDk4Y9yADCsAcmRsokLoPSW3sswna63WxuUsYJx
Qj41NZ0WDXGw79qH9Km9WNY8XVi7jhlrxj+/eo/7glLnJbBhQXhwKNQOJJve6Y+C
1dwRG90CgYEA/bcNkDu3nBNhmxNqhoDLa89PY8pubnWwBt+g7LiK4JY9SVnV0939
hWwknB3Fwb4eZpTyplOg5enBfE+5TYF6yD4PBe6LIrgZPRFhUmqv69IxhbYBQVdK
OOJC9NIzQa97YtWQbr27NBF28Kg0XG/M5KeZI5R1CCm3Az6Nb+V39g8CgYEA6XIJ
TMImCyeXdYLMzZrFQ76kTy5QLUsiz1ErtMIUGrFP8+GcbTqv7RqTGnUUywxFxl0d
HGEdojncsaUxECl6k1CDkN7sRWEwaZYuIgjHobnR/Ml3guZhppBYbKVHIgoJYl++
1jYGm0vv4ThPP3X4LIYi+L1kqzEiMTXUQje7WP8CgYA2zHG+hFds+3aqcvAgKvNE
ztr7174wV3T0lxp/5oAAcBK+pAueHiViyu/BVGDhLroMOAGM0SVAY1OqEOIaDmxB
kb3W7yXYEuFIoldd4LMsgRK3KY9t2Hesf5osnLX7avaAa1YtxQcJAPiqk/nzOSaN
AHIAEFGSuMfRiJIt5+cYGwKBgQCLa/0b59Ra0yGFIEJ7BVWNWT2+wJVIB9jZYsws
n8DsFDwBFxyaq/NIM5fptEVf44uEpjXtrh7Jta8WNeewlGVNuEdrbYhFA3+rf/M7
hmzcLSO5ywVim390wD7fXHK4ZLAoHbDXNNsLQvYYV84Uw7sdtFsMneiSybx3EYQc
rDZrNwKBgAoC1PNdy6JGDVF6DzMyZUeZ/EaVRcLNwcRjMq2qXOdCpY7QiKf0yU/w
CX6BeHaMdTyTHy8QqNEyF1Vp5VV1SCInuM76NOpt/HOh0pISmvLS0WW37lUXbm4w
AhZiSp2LgUzicx6J/9VTVP6KyH0VhJbx4gnzQYGbEsFlUwYcX+Uk
-----END RSA PRIVATE KEY-----
""".trimIndent()

private val PKCS8_PEM = """
-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDnXKAT0tE6Pb60
VEcTxN1zkCdaCaHlP8TbRkwQ6Y3/Y3TO3EuUndceSKSIBMCr5sHqRW9ED2a0knIW
Y7yrrkAaQ8MdJYJAW4GTYwxj3jUkp06o/A0uDdDyzlh4+A7P0BKdtt3LMgNikASh
BD8iE4XlPIoNOO0k/TyIxwIQ2RZf27kmliso8LNaKLE8vMUR/X9W7AMvPjaHzDXQ
uzhp+2wwFjoKKUZeb4lzNH7G2dSD9ia26IEWIno3AuqfIPvcIekG7Uzmg9qSQR+E
LB1T4IwJySDgd3ci9UZZN1mrJz+aDl6Vkwuv4K1tjHFc4LeCAevQNDQHKj5rS2fD
mbLFCEDxAgMBAAECggEAH5ItKedXs7nfPx5NAK6ZhX6DUC8GT5EvHLjwAlLMlLAO
rXe0PJh5VWGWShVT8WEwzvYgo8nyOuvM868yfL7vcmYSxvRqXLvf56vZcftNhy3p
dvfGGKW8AJYVzpQS+VqsX6cnhuXRPm7mos5Aizbcy5ZCRGGjQqvnycZWStRE0FmZ
5RytHwWfXtYR9wc4jff1NrhjwbwR89rtqJZXNFyJ78FZth+VcZp1pFm2vBm2wOTh
j3IAMKwByZGyiQug9JbeyzCdrrdbG5SxgnFCPjU1nRYNcbDv2of0qb1Y1jxdWLuO
GWvGP796j/uCUuclsGFBeHAo1A4km97pj4LV3BEb3QKBgQD9tw2QO7ecE2GbE2qG
gMtrz09jym5udbAG36DsuIrglj1JWdXT3f2FbCScHcXBvh5mlPKmU6Dl6cF8T7lN
gXrIPg8F7osiuBk9EWFSaq/r0jGFtgFBV0o44kL00jNBr3ti1ZBuvbs0EXbwqDRc
b8zkp5kjlHUIKbcDPo1v5Xf2DwKBgQDpcglMwiYLJ5d1gszNmsVDvqRPLlAtSyLP
USu0whQasU/z4ZxtOq/tGpMadRTLDEXGXR0cYR2iOdyxpTEQKXqTUIOQ3uxFYTBp
li4iCMehudH8yXeC5mGmkFhspUciCgliX77WNgabS+/hOE8/dfgshiL4vWSrMSIx
NdRCN7tY/wKBgDbMcb6EV2z7dqpy8CAq80TO2vvXvjBXdPSXGn/mgABwEr6kC54e
JWLK78FUYOEuugw4AYzRJUBjU6oQ4hoObEGRvdbvJdgS4UiiV13gsyyBErcpj23Y
d6x/miyctftq9oBrVi3FBwkA+KqT+fM5Jo0AcgAQUZK4x9GIki3n5xgbAoGBAItr
/Rvn1FrTIYUgQnsFVY1ZPb7AlUgH2NlizCyfwOwUPAEXHJqr80gzl+m0RV/ji4Sm
Ne2uHsm1rxY157CUZU24R2ttiEUDf6t/8zuGbNwtI7nLBWKbf3TAPt9ccrhksCgd
sNc02wtC9hhXzhTDux20Wwyd6JLJvHcRhBysNms3AoGACgLU813LokYNUXoPMzJl
R5n8RpVFws3BxGMyrapc50KljtCIp/TJT/AJfoF4dox1PJMfLxCo0TIXVWnlVXVI
Iie4zvo06m38c6HSkhKa8tLRZbfuVRdubjACFmJKnYuBTOJzHon/1VNU/orIfRWE
lvHiCfNBgZsSwWVTBhxf5SQ=
-----END PRIVATE KEY-----
""".trimIndent()

private fun config(
    githubToken: String? = null,
    authMode: String = "pat",
    appId: String? = null,
    appPrivKey: String? = null,
    appInstallId: String? = null,
) = TenantAiConfigRecord(
    tenantId = 1,
    provider = "openai",
    model = "gpt-4o",
    apiKeyEnc = "k",
    githubTokenEnc = githubToken,
    githubBaseBranch = "main",
    githubAuthMode = authMode,
    githubAppIdEnc = appId,
    githubAppPrivKeyEnc = appPrivKey,
    githubAppInstallIdEnc = appInstallId,
    monthlyTokenBudget = null,
    tokensUsedMonth = 0,
    isActive = true,
    ddApiKeyEnc = null,
    ddAppKeyEnc = null,
    createdAt = OffsetDateTime.now(),
    updatedAt = OffsetDateTime.now(),
)

class GithubAppAuthTest {

    @Test
    fun `parses PKCS1 key and matches the equivalent PKCS8 key`() {
        val fromPkcs1 = parseRsaPrivateKey(PKCS1_PEM)
        val fromPkcs8 = parseRsaPrivateKey(PKCS8_PEM)

        assertEquals(2048, fromPkcs1.modulus.bitLength())
        assertEquals(fromPkcs8.modulus, fromPkcs1.modulus, "mesma chave deve produzir o mesmo módulo")
        assertEquals(fromPkcs8.privateExponent, fromPkcs1.privateExponent)
    }

    @Test
    fun `returns NotConfigured when neither PAT nor app is set`() = runBlocking {
        assertEquals(GithubTokenResult.NotConfigured, resolveGithubToken(null))
        assertEquals(GithubTokenResult.NotConfigured, resolveGithubToken(config()))
        assertEquals(GithubTokenResult.NotConfigured, resolveGithubToken(config(githubToken = "")))
    }

    @Test
    fun `uses PAT when auth mode is pat`() = runBlocking {
        val result = resolveGithubToken(config(githubToken = "ghp_fake", authMode = "pat"))
        assertEquals(GithubTokenResult.Ok("ghp_fake"), result)
    }

    @Test
    fun `reports app error for invalid private key in github_app mode`() = runBlocking {
        val result = resolveGithubToken(
            config(authMode = "github_app", appId = "123", appPrivKey = "not-a-valid-pem", appInstallId = "999"),
        )
        assertTrue(result is GithubTokenResult.Error, "chave inválida deve resultar em Error, não NotConfigured")
    }
}
