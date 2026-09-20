package com.campuschat.app.core.crypto

object ApiProbe {
    fun probe() {
        try {
            val curveClass = Class.forName("org.signal.libsignal.protocol.ecc.Curve")
            println("Curve class found: ${curveClass.name}")
            curveClass.methods.forEach { println("  Curve method: ${it.name}") }
        } catch (e: Exception) {
            println("Curve error: $e")
        }

        try {
            val senderKeyStoreClass = Class.forName("org.signal.libsignal.protocol.state.SenderKeyStore")
            println("SenderKeyStore class found: ${senderKeyStoreClass.name}")
            senderKeyStoreClass.methods.forEach { println("  SenderKeyStore method: ${it.name}") }
        } catch (e: Exception) {
            println("SenderKeyStore error: $e")
        }

        try {
            val kemClass = Class.forName("org.signal.libsignal.protocol.kem.KEMPublicKey")
            println("KEMPublicKey class found: ${kemClass.name}")
            kemClass.constructors.forEach { println("  KEMPublicKey constructor: (${it.parameterTypes.joinToString { p -> p.name }})") }
        } catch (e: Exception) {
            println("KEMPublicKey error: $e")
        }
    }
}
