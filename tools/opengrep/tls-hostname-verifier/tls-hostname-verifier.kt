import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

// ruleid: tls-hostname-verifier
val unsafe = HostnameVerifier { host, session -> true }

val alsoUnsafe =
    // ruleid: tls-hostname-verifier
    javax.net.ssl.HostnameVerifier { _, _ ->
        true
    }

// ok: tls-hostname-verifier
val verified = HostnameVerifier { host, session ->
    HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
}

// ok: tls-hostname-verifier
val reject = HostnameVerifier { _, _ -> false }
