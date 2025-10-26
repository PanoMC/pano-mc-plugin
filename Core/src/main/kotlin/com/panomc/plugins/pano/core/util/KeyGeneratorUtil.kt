package com.panomc.plugins.pano.core.util

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom

object KeyGeneratorUtil {
    fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())

        val keyPair = keyGen.generateKeyPair()

        return keyPair
    }
}