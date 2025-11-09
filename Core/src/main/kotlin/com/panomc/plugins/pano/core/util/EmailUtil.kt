package com.panomc.plugins.pano.core.util

object EmailUtil {
    /**
     * Masks an email address for privacy.
     * 
     * Examples:
     * - test@example.com -> t**t@e***e.com
     * - user@domain.co.uk -> u**r@d***n.co.uk
     * - a@b.com -> a@b.com (too short to mask)
     * 
     * @param email The email address to mask
     * @return Masked email address, or original email if it's invalid or too short
     */
    fun maskEmail(email: String?): String {
        if (email.isNullOrBlank()) {
            return email ?: ""
        }

        val trimmedEmail = email.trim()
        
        // Check if it's a valid email format
        if (!trimmedEmail.contains("@")) {
            return trimmedEmail
        }

        val parts = trimmedEmail.split("@")
        if (parts.size != 2) {
            return trimmedEmail
        }

        val localPart = parts[0]
        val domainPart = parts[1]

        // Mask local part
        val maskedLocal = when {
            localPart.length <= 1 -> localPart
            localPart.length == 2 -> "${localPart[0]}*"
            else -> "${localPart[0]}${"*".repeat(localPart.length - 2)}${localPart[localPart.length - 1]}"
        }

        // Mask domain part (preserve TLD)
        val domainParts = domainPart.split(".")
        if (domainParts.size < 2) {
            // If no TLD, mask the whole domain
            val maskedDomain = when {
                domainPart.length <= 1 -> domainPart
                domainPart.length == 2 -> "${domainPart[0]}*"
                else -> "${domainPart[0]}${"*".repeat(domainPart.length - 2)}${domainPart[domainPart.length - 1]}"
            }
            return "$maskedLocal@$maskedDomain"
        }

        val domainName = domainParts.dropLast(1).joinToString(".")
        val tld = domainParts.last()

        val maskedDomainName = when {
            domainName.length <= 1 -> domainName
            domainName.length == 2 -> "${domainName[0]}*"
            else -> "${domainName[0]}${"*".repeat(domainName.length - 2)}${domainName[domainName.length - 1]}"
        }

        return "$maskedLocal@$maskedDomainName.$tld"
    }
}

