package io.github.kimgeonwoo718.roki.model

import io.github.kimgeonwoo718.roki.BuildConfig

/**
 * Everything the app needs to know about one .litertlm model file.
 *
 * Sizes and SHA-256 values come from the Hugging Face API (lfs.sha256) at the pinned commit, so a
 * download can be verified byte-for-byte. They are public metadata, not secrets.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val hfRepo: String,
    val hfCommit: String,
    /** Gated repos need a logged-in Hugging Face account, so the app cannot download them. */
    val gated: Boolean,
    /**
     * KV-cache size passed to EngineConfig.maxNumTokens: the whole conversation (system prompt,
     * history, new input and output) must fit. When it fills mid-answer the reply just ends.
     */
    val maxNumTokens: Int,
    val minDeviceMemoryGb: Int,
    val defaultTopK: Int,
    val defaultTopP: Double,
    val defaultTemperature: Double,
) {
    val pinnedUrl: String
        get() = "https://huggingface.co/$hfRepo/resolve/$hfCommit/$fileName"

    /** URL the in-app downloader uses, or null when the model can only be side-loaded with adb. */
    val downloadUrl: String?
        get() = BuildConfig.MODEL_DOWNLOAD_URL.ifBlank { if (gated) null else pinnedUrl }
}

object ModelCatalog {
    val GEMMA_4_E2B =
        ModelSpec(
            id = "gemma-4-e2b",
            displayName = "Gemma 4 E2B",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712L,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            hfRepo = "litert-community/gemma-4-E2B-it-litert-lm",
            // Same commit the official AI Edge Gallery pins (model_allowlists/1_0_20.json).
            hfCommit = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
            gated = false,
            // The file supports up to 32K. Must be set explicitly: null means 4096 on Android GPU.
            // Starting at 8K until memory use on the device is measured.
            maxNumTokens = 8192,
            minDeviceMemoryGb = 8,
            defaultTopK = 64,
            defaultTopP = 0.95,
            defaultTemperature = 1.0,
        )

    /** Gated on Hugging Face: side-load only (see README). */
    val GEMMA_3N_E2B =
        ModelSpec(
            id = "gemma-3n-e2b",
            displayName = "Gemma 3n E2B",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            sizeBytes = 3_655_827_456L,
            sha256 = "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6",
            hfRepo = "google/gemma-3n-E2B-it-litert-lm",
            hfCommit = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
            gated = true,
            maxNumTokens = 4096,
            minDeviceMemoryGb = 8,
            defaultTopK = 64,
            defaultTopP = 0.95,
            defaultTemperature = 1.0,
        )

    /** Gated on Hugging Face: side-load only (see README). */
    val GEMMA_3N_E4B =
        ModelSpec(
            id = "gemma-3n-e4b",
            displayName = "Gemma 3n E4B",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            sizeBytes = 4_919_541_760L,
            sha256 = "2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4",
            hfRepo = "google/gemma-3n-E4B-it-litert-lm",
            hfCommit = "297ed75955702dec3503e00c2c2ecbbf475300bc",
            gated = true,
            maxNumTokens = 4096,
            minDeviceMemoryGb = 12,
            defaultTopK = 64,
            defaultTopP = 0.95,
            defaultTemperature = 1.0,
        )

    val all = listOf(GEMMA_4_E2B, GEMMA_3N_E2B, GEMMA_3N_E4B)

    /** Selected at build time with the Gradle property roki.modelId. */
    val active: ModelSpec = all.firstOrNull { it.id == BuildConfig.MODEL_ID } ?: GEMMA_4_E2B
}
