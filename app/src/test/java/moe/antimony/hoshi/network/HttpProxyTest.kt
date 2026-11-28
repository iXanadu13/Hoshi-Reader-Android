package moe.antimony.hoshi.network

import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpProxyTest {
    @Test
    fun parsesHttpProxyAddressesAndCanonicalizesThem() {
        assertEquals("http://127.0.0.1:7890", parseHttpProxy("http://127.0.0.1:7890")?.address)
        assertEquals("http://127.0.0.1:7890", parseHttpProxy(" 127.0.0.1:7890 ")?.address)
        assertEquals("http://[::1]:7890", parseHttpProxy("[::1]:7890")?.address)
    }

    @Test
    fun rejectsNonHttpOrUnsafeProxyAddresses() {
        listOf(
            "",
            "https://proxy.example:7890",
            "http://user:password@proxy.example:7890",
            "http://proxy.example:0",
            "http://proxy.example:65536",
            "http://proxy.example:7890/path",
            "http://proxy.example:7890?token=secret",
        ).forEach { assertNull(it, parseHttpProxy(it)) }
    }

    @Test
    fun selectorUsesManualProxyForHttpAndHttpsButKeepsInternalHostsDirect() {
        val selector = HttpProxySelector(null)
        selector.proxy = parseHttpProxy("127.0.0.1:7890")

        assertEquals(Proxy.Type.HTTP, selector.select(URI("http://example.com")).single().type())
        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://example.com")).single().type())
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://localhost:8765")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://appassets.androidplatform.net/")))
        assertTrue(selector.select(URI("http://example.com")).single().address()!!.toString().contains("7890"))
    }

    @Test
    fun selectorRejectsProxyForInternalHostsEvenWhenDelegateExists() {
        val selector = HttpProxySelector(object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("proxy", 8080)))
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        selector.proxy = parseHttpProxy("127.0.0.1:7890")

        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://127.0.0.1:8765")))
    }
}
