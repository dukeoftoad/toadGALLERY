package io.github.dukeoftoad.gallery.ml.engine

/**
 * The pinned model catalog for the Ente-derived on-device AI engine
 * (`docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`, "Model
 * inventory and delivery implications").
 *
 * Every entry mirrors the vendored engine's Rust catalog at the pinned Ente
 * commit `423b2d80d9ec89d45f5680cb1e1d777bac225f41`
 * (`engine/crates/ente-ml/src/assets.rs`, `ModelAssetSpec`). The audit is
 * explicit: "These names, sizes and SHA-256 values must be pinned to the exact
 * chosen Ente commit before implementation; do not download 'latest' model
 * names dynamically or silently swap weights." This file is that pin. A
 * manifest change here MUST be accompanied by an engine re-pin (see
 * `engine/NOTICE.md`) and invalidates every installed copy of the affected
 * model (the SHA-256 doubles as the model version).
 *
 * Delivery policy (ADR-0020): models are downloaded on demand from the pinned
 * allowlisted host, verified against [Model.spec] (expected byte size AND
 * SHA-256) before staged atomic installation, and are never shipped in the
 * APK. Inference is 100% on-device; only inert model bytes cross the network.
 */
object ModelManifest {

    /**
     * Coarse invalidation groups. A model update invalidates exactly the AI
     * outputs derived from its capability --- never more, never less.
     */
    enum class Capability {
        /** Face detection + face embedding: invalidates face embeddings and clusters. */
        FACE,

        /** CLIP image/text encoders + BPE vocabulary: invalidates image embeddings and the vector index. */
        SEMANTIC,

        /**
         * Pet detection + species embeddings. Pinned for catalog fidelity with
         * the engine's Rust catalog, but deliberately NOT provisioned in the
         * first delivery (audit: "do not include the extra model pack in the
         * first delivery unless explicitly desired").
         */
        PET,
    }

    /** One pinned asset: name, expected size, expected SHA-256, capability. */
    data class Model(
        /** Exact on-disk file name, identical to the Rust catalog's asset name. */
        val fileName: String,
        val capability: Capability,
        /** Expected byte size; checked before hashing (cheap rejection first). */
        val sizeBytes: Long,
        /** Expected SHA-256, lowercase hex. Also the model's version token. */
        val sha256: String,
    ) {
        val isPet: Boolean get() = capability == Capability.PET
    }

    val FACE_DETECTION = Model(
        fileName = "yolov5s_face_640_640_static_b1.onnx",
        capability = Capability.FACE,
        sizeBytes = 32_355_091L,
        sha256 = "e047647409403d52696035ecd445792173e50d7fbdcccac97b958a585db9aa3d",
    )

    val FACE_EMBEDDING = Model(
        fileName = "mobilefacenet_portable_static_b1.onnx",
        capability = Capability.FACE,
        sizeBytes = 5_278_803L,
        sha256 = "0763fc33f54e138476194da95987e133b3e976075a6b1d3e1b2caedb251b1a36",
    )

    val CLIP_IMAGE = Model(
        fileName = "mobileclip_s2_image_gelu_opset20.onnx",
        capability = Capability.SEMANTIC,
        sizeBytes = 143_057_352L,
        sha256 = "205a430af825e501c5138e5bb9abea942482a7a4fd4a680e98e47cf0830dce7e",
    )

    val CLIP_TEXT = Model(
        fileName = "mobileclip_s2_text_opset18_quant.onnx",
        capability = Capability.SEMANTIC,
        sizeBytes = 67_144_712L,
        sha256 = "d92f33dfcff83077fc2e0d3414250710efbb51795dfd89767bdbefb5fdc47322",
    )

    val CLIP_VOCAB = Model(
        fileName = "bpe_simple_vocab_16e6.txt",
        capability = Capability.SEMANTIC,
        sizeBytes = 3_194_984L,
        sha256 = "67603cfda2e032ad77b5f8808af37789d590db664b26df8705d2bf8b3c553fc8",
    )

    // Pet pack (capability pinned; not provisioned in the first delivery).
    // Names mirror the Rust catalog exactly: PetFaceDetection,
    // PetFaceEmbedding{Dog,Cat}, PetBodyDetection, PetBodyEmbedding{Dog,Cat}.

    val PET_FACE_DETECTION = Model(
        fileName = "yolov5s_pet_face_fp16_V2.onnx",
        capability = Capability.PET,
        sizeBytes = 14_758_020L,
        sha256 = "7876d97992eeb5f3a9f3b35eff5e0e133012928172a8b005093108d8c3ad2d1c",
    )

    val PET_FACE_EMBEDDING_DOG = Model(
        fileName = "dog_face_embedding128.onnx",
        capability = Capability.PET,
        sizeBytes = 4_141_071L,
        sha256 = "fb04d781eb1f7adf6ce3432dc0c5873f16cc051b5c98c14c754afb39e2b92462",
    )

    val PET_FACE_EMBEDDING_CAT = Model(
        fileName = "cat_face_embedding128.onnx",
        capability = Capability.PET,
        sizeBytes = 4_141_071L,
        sha256 = "32b10694a27f6404d2beaddbd64f07ad555f72dccb12ee60a7afe5dcf6aad6cd",
    )

    val PET_BODY_DETECTION = Model(
        fileName = "yolov5s_object_fp16.onnx",
        capability = Capability.PET,
        sizeBytes = 14_987_107L,
        sha256 = "113f0c18632eb2c4f6deebcd40eb01c676492e9b43923c2d336e1b4012fce9ef",
    )

    val PET_BODY_EMBEDDING_DOG = Model(
        fileName = "dog_body_embedding192.onnx",
        capability = Capability.PET,
        sizeBytes = 4_569_361L,
        sha256 = "1d85aa20358137e30f11c2d0baa9a2248b9997928d501fe15365d1fc57522770",
    )

    val PET_BODY_EMBEDDING_CAT = Model(
        fileName = "cat_body_embedding192.onnx",
        capability = Capability.PET,
        sizeBytes = 4_569_361L,
        sha256 = "62fb5891e61be69a96510d8ec56e7525a9541b0283e54574d27c86c9b4a26ddf",
    )

    /** All pinned assets, in catalog order. */
    val all: List<Model> = listOf(
        FACE_DETECTION,
        FACE_EMBEDDING,
        CLIP_IMAGE,
        CLIP_TEXT,
        CLIP_VOCAB,
        PET_FACE_DETECTION,
        PET_FACE_EMBEDDING_DOG,
        PET_FACE_EMBEDDING_CAT,
        PET_BODY_DETECTION,
        PET_BODY_EMBEDDING_DOG,
        PET_BODY_EMBEDDING_CAT,
    )

    /** The assets a capability needs before it is inference-ready. */
    fun forCapability(capability: Capability): List<Model> =
        all.filter { it.capability == capability }

    /** Models provisioned in the first delivery (pet pack deliberately excluded). */
    val firstDelivery: List<Model> = all.filterNot { it.isPet }

    fun byFileName(fileName: String): Model? = all.firstOrNull { it.fileName == fileName }
}
