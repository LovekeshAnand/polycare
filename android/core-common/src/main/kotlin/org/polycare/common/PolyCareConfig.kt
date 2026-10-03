package org.polycare.common

/** Every tunable threshold on the edge lives here. No magic numbers inline. */
object PolyCareConfig {

    object Routing {
        /** Below this top skill score, answer with the base model and log a gap (τ). */
        const val minSkillScore = 0.60f
        /** If the top two skills are closer than this, blend them (δ). */
        const val blendMargin = 0.10f
        /** Softmax temperature for blend weights (T). */
        const val blendTemperature = 0.05f
        const val skillCandidates = 3
    }

    object Retrieval {
        const val prefetchLimit = 20
        const val resultLimit = 5
        /** Standard RRF constant. */
        const val rrfK = 60
        /** BM25 term-frequency saturation and length normalisation for sparse vectors. */
        const val bm25K1 = 1.2f
        const val bm25B = 0.75f
        const val bm25AvgDocTokens = 120f

        /** Staleness half-life for the confidence badge. */
        const val stalenessHalfLifeMs = 14L * 24 * 60 * 60 * 1000
    }

    object Ocr {
        /** Longest image side (px) the text detector sees. 960 turns a photographed page's body text into smudges. */
        const val detectorMaxSidePx = 1536
        /** A line whose best recognizer is less sure than this is dropped as noise. */
        const val minLineConfidence = 0.3f
    }

    object Conflicts {
        const val nearDuplicateCosine = 0.90f
    }

    object Gaps {
        /** Longest question kept for a supervisor; longer input is cut. */
        const val maxQueryChars = 240
    }

    object Radar {
        /** Signals older than this no longer count toward a cluster. */
        const val windowMs = 7L * 24 * 60 * 60 * 1000
        /** Cosine similarity at or above which two signals belong to the same syndrome cluster. */
        const val clusterCosine = 0.80f
        /** A cluster needs at least this many signals... */
        const val minSignals = 3
        /** ...from at least this many distinct villages to raise an alert. */
        const val minVillages = 2
        /** Vector model for on-device signals: a multi-hot encoding of the marked danger signs. */
        const val signalModelId = "danger-signs-v1"
    }

    object Sync {
        const val stableWindowMs = 30_000L
        const val chunkBytes = 256 * 1024
        const val maxClockDriftMs = 5L * 60 * 1000
        /** How often the background job runs when its constraints hold. */
        const val periodicHours = 6L
        /** Most a single background run sends on a metered (data-plan) connection. */
        const val meteredBudgetBytes = 64L * 1024
    }

    object Llm {
        /** Context window: prompt + generated tokens must fit inside this many tokens. */
        const val contextTokens = 2048
        /** A hard safety cap, not a target — the system prompt already asks for 2-4 sentences
         * and the model stops at EOS well before this in practice (142 tokens was the longest
         * real generation observed this session). Lower than the previous 256 so a model that
         * fails to stop doesn't run needlessly long, without being tight enough to truncate a
         * normal answer. */
        const val maxNewTokens = 120
        /** 0 = greedy. Answers only restate a retrieved passage, so determinism costs nothing
         * and enables exact prompt-lookup speculative decoding (see jni_bridge.cpp). */
        const val temperature = 0f
        const val topP = 0.9f
        /** Max tokens drafted from the prompt per verification step; 0 disables speculation. */
        const val speculativeDraftTokens = 8
        /** Per-request LoRA scale when a single skill is the clear match (no blending). */
        const val singleSkillScale = 1.0f
    }

    object Governor {
        /**
         * Minimum RAM (MB) as reported by the OS, which is always below the advertised size:
         * a "6 GB" phone reports ~5.3 GB, a "4 GB" phone ~3.6 GB, a "3 GB" phone ~2.7 GB.
         */
        const val fullRamMb = 5_000L // 6 GB class: 1.5B model with two adapters
        const val leanRamMb = 3_400L // 4 GB class: 1.5B model, shorter context
        const val baseRamMb = 2_500L // 3 GB class: small model
        const val lowBatteryPct = 15
        /** Free storage (MB) needed to hold a ~1 M point knowledge slice plus models. */
        const val fullStorageMb = 3_000L
    }
}
