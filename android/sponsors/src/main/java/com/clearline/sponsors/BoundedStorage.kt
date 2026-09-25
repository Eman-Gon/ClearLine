package com.clearline.sponsors

internal interface CredentialBlobStore {
    fun read(service: String): ByteArray?
    fun write(service: String, envelope: ByteArray)
    fun delete(service: String)
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Input exceeds bounded size" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
