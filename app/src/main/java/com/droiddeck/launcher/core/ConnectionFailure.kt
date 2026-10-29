package com.droiddeck.launcher.core

enum class ConnectionFailure {
    AUTH,
    TLS,
    UNREACHABLE,
    UNKNOWN,
}

fun classifyConnectionFailure(className: String): ConnectionFailure = when (className) {
    "AdbPairAuthException" -> ConnectionFailure.AUTH
    "SSLHandshakeException", "SSLException", "CertificateException" -> ConnectionFailure.TLS
    "ConnectException", "SocketTimeoutException", "UnknownHostException", "NoRouteToHostException" -> ConnectionFailure.UNREACHABLE
    else -> ConnectionFailure.UNKNOWN
}
