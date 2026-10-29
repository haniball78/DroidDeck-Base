package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionFailureTest {

    @Test fun authExceptionMapsToAuth() {
        assertEquals(ConnectionFailure.AUTH, classifyConnectionFailure("AdbPairAuthException"))
    }

    @Test fun tlsExceptionsMapToTls() {
        assertEquals(ConnectionFailure.TLS, classifyConnectionFailure("SSLHandshakeException"))
        assertEquals(ConnectionFailure.TLS, classifyConnectionFailure("SSLException"))
        assertEquals(ConnectionFailure.TLS, classifyConnectionFailure("CertificateException"))
    }

    @Test fun networkExceptionsMapToUnreachable() {
        assertEquals(ConnectionFailure.UNREACHABLE, classifyConnectionFailure("ConnectException"))
        assertEquals(ConnectionFailure.UNREACHABLE, classifyConnectionFailure("SocketTimeoutException"))
        assertEquals(ConnectionFailure.UNREACHABLE, classifyConnectionFailure("UnknownHostException"))
        assertEquals(ConnectionFailure.UNREACHABLE, classifyConnectionFailure("NoRouteToHostException"))
    }

    @Test fun anythingElseMapsToUnknown() {
        assertEquals(ConnectionFailure.UNKNOWN, classifyConnectionFailure("IllegalStateException"))
        assertEquals(ConnectionFailure.UNKNOWN, classifyConnectionFailure(""))
    }
}
