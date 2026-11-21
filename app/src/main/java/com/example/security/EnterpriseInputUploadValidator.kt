package com.example.security

import java.util.Locale

/**
 * Enterprise Input Schema, Path Traversal & Upload Security Validator (PDF Sections 7 & 8).
 *
 * Enforces:
 * - Strict MIME / extension allowlisting for student/staff portraits and study material attachments.
 * - Explicit rejection of executable payloads (`.exe`, `.sh`, `.bat`, `.cmd`, `.apk`, `.dex`, `.so`, `.elf`, `.js`, `.jsp`, `.php`, `.py`).
 * - Path traversal prevention (`..`, `../`, `..\`, `%2e%2e`, null bytes `\u0000`).
 * - Maximum size limits on inline `data:` URIs and text fields.
 * - Safe filename sanitization before storage or metadata persistence.
 */
object EnterpriseInputUploadValidator {

    const val MAX_INLINE_DATA_URI_CHARS: Int = 512_000 // ~384 KB binary payload cap for Firestore-safe inline portraits
    const val MAX_FILENAME_LENGTH: Int = 200

    private val ALLOWED_IMAGE_MIMES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp"
    )

    private val ALLOWED_DOCUMENT_EXTENSIONS = setOf(
        "pdf", "png", "jpg", "jpeg", "webp", "txt", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "csv", "mp4"
    )

    private val FORBIDDEN_EXECUTABLE_EXTENSIONS = setOf(
        "exe", "dll", "so", "dex", "apk", "aab", "jar", "class",
        "sh", "bash", "bat", "cmd", "ps1", "vbs", "js", "mjs",
        "php", "jsp", "jspx", "asp", "aspx", "py", "rb", "pl", "cgi", "elf", "msi", "scr"
    )

    data class UploadValidationResult(
        val valid: Boolean,
        val sanitizedName: String,
        val reason: String
    )

    /**
     * Validates and sanitizes an uploaded file name or attachment label.
     * Rejects path traversal sequences (`..`, `/`, `\`, `%2e%2e`, null bytes) and executable extensions.
     */
    fun validateAndSanitizeFileName(rawFileName: String): UploadValidationResult {
        val trimmed = rawFileName.trim()
        if (trimmed.isEmpty()) {
            return UploadValidationResult(valid = true, sanitizedName = "", reason = "Empty filename allowed")
        }
        if (trimmed.length > MAX_FILENAME_LENGTH) {
            return UploadValidationResult(
                valid = false,
                sanitizedName = "",
                reason = "Attachment filename exceeds maximum length ($MAX_FILENAME_LENGTH chars)."
            )
        }
        val lower = trimmed.lowercase(Locale.US)
        if (trimmed.contains('\u0000') ||
            lower.contains("..") ||
            lower.contains("%2e%2e") ||
            lower.contains("%2f") ||
            lower.contains("%5c") ||
            trimmed.startsWith("/") ||
            trimmed.startsWith("\\")
        ) {
            return UploadValidationResult(
                valid = false,
                sanitizedName = "",
                reason = "Path traversal sequence detected in filename."
            )
        }

        // Check extension if file-like (ignore verified Aadhaar badge strings like "Verified Aadhaar: XXXX-XXXX-1234")
        if (!trimmed.startsWith("Verified Aadhaar:") && trimmed.contains('.')) {
            val ext = lower.substringAfterLast('.', "").trim()
            if (ext in FORBIDDEN_EXECUTABLE_EXTENSIONS) {
                return UploadValidationResult(
                    valid = false,
                    sanitizedName = "",
                    reason = "Executable or script file extension '.$ext' is strictly prohibited."
                )
            }
            // Reject double-extension spoofing such as "syllabus.pdf.exe" or "photo.jpg.sh"
            val parts = lower.split('.').drop(1)
            if (parts.any { it in FORBIDDEN_EXECUTABLE_EXTENSIONS }) {
                return UploadValidationResult(
                    valid = false,
                    sanitizedName = "",
                    reason = "Disguised double-extension executable payload is prohibited."
                )
            }
            if (ext.isNotEmpty() && ext !in ALLOWED_DOCUMENT_EXTENSIONS) {
                return UploadValidationResult(
                    valid = false,
                    sanitizedName = "",
                    reason = "Unsupported file extension '.$ext'."
                )
            }
        }

        val sanitized = trimmed
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .take(MAX_FILENAME_LENGTH)
        return UploadValidationResult(valid = true, sanitizedName = sanitized, reason = "OK")
    }

    /**
     * Validates a portrait photo URI (`data:image/...;base64,...`, `content://...`, `https://...`, or empty).
     */
    fun validatePhotoOrAttachmentUri(rawUri: String): UploadValidationResult {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) {
            return UploadValidationResult(valid = true, sanitizedName = "", reason = "Empty URI allowed")
        }
        if (trimmed.length > MAX_INLINE_DATA_URI_CHARS) {
            return UploadValidationResult(
                valid = false,
                sanitizedName = "",
                reason = "Upload payload exceeds maximum permitted size (${MAX_INLINE_DATA_URI_CHARS} bytes)."
            )
        }
        val lower = trimmed.lowercase(Locale.US)
        if (trimmed.contains('\u0000') || lower.contains("../") || lower.contains("..\\") || lower.contains("%2e%2e")) {
            return UploadValidationResult(
                valid = false,
                sanitizedName = "",
                reason = "Path traversal sequence detected in URI."
            )
        }
        if (lower.startsWith("javascript:") || lower.startsWith("vbscript:") || lower.startsWith("file://")) {
            return UploadValidationResult(
                valid = false,
                sanitizedName = "",
                reason = "Unsafe URI scheme is strictly prohibited."
            )
        }
        if (lower.startsWith("data:")) {
            val mimeHeader = lower.substringAfter("data:").substringBefore(";").substringBefore(",")
            if (mimeHeader !in ALLOWED_IMAGE_MIMES) {
                return UploadValidationResult(
                    valid = false,
                    sanitizedName = "",
                    reason = "Disallowed inline data URI MIME type '$mimeHeader'. Only JPEG, PNG, and WebP images are permitted."
                )
            }
        }
        return UploadValidationResult(valid = true, sanitizedName = trimmed, reason = "OK")
    }
}
