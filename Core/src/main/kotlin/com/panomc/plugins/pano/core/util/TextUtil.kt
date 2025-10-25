package com.panomc.plugins.pano.core.util

object TextUtil {
    fun String.convertToSnakeCase(): String {
        val regex = Regex("([a-z])([A-Z])")
        val result = regex.replace(this) { matchResult ->
            "${matchResult.groupValues[1]}_${matchResult.groupValues[2].lowercase()}"
        }
        return result
    }
}