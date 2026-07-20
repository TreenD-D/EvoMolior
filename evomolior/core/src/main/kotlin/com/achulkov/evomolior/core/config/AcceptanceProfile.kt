package com.achulkov.evomolior.core.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Operator-selected acceptance authority for a run.
 *
 * [NORMAL] is the ordinary runtime authority. The other profiles are explicit acceptance
 * envelopes and must not be inferred from an objective, command name, or output path.
 *
 * @param id Stable persisted and command-line identifier.
 */
@Serializable(with = AcceptanceProfileSerializer::class)
enum class AcceptanceProfile(val id: String) {
    NORMAL("normal"),
    RUNTIME_USABILITY_SMOKE("runtime-usability-smoke"),
    APPENDIX_B_FULL("appendix-b-full");

    companion object {
        /**
         * Parses a stable profile identifier or enum name.
         *
         * @param value Profile identifier or enum name.
         * @return Parsed acceptance profile.
         * @throws IllegalArgumentException when [value] is unsupported.
         */
        fun parse(value: String): AcceptanceProfile = entries.firstOrNull {
            it.id.equals(value, ignoreCase = true) ||
                it.name.equals(value, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Unsupported acceptance profile '$value'. Supported profiles: " +
                entries.joinToString(", ") { it.id }
        )
    }
}

/**
 * Persists [AcceptanceProfile] by its stable identifier instead of its Kotlin enum name.
 */
internal object AcceptanceProfileSerializer : KSerializer<AcceptanceProfile> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "com.achulkov.evomolior.core.config.AcceptanceProfile",
        kind = PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: AcceptanceProfile) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): AcceptanceProfile =
        AcceptanceProfile.parse(decoder.decodeString())
}
