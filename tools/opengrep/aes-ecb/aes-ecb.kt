import javax.crypto.Cipher

fun insecure() {
    // ruleid: aes-ecb
    Cipher.getInstance("AES")
    // ruleid: aes-ecb
    Cipher.getInstance("AES/ECB/PKCS5Padding")
    // ruleid: aes-ecb
    javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
    // ruleid: aes-ecb
    Cipher.getInstance("AES/ECB/PKCS5Padding", "Provider")
}

fun safe(mode: String) {
    // ok: aes-ecb
    Cipher.getInstance("AES/GCM/NoPadding")
    // ok: aes-ecb
    Cipher.getInstance("AES/CBC/PKCS5Padding")
    // ok: aes-ecb
    Cipher.getInstance(mode)
}
