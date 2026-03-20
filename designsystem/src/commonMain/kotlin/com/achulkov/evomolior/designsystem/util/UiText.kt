package com.achulkov.evomolior.designsystem.util

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource


sealed interface UiText {

    companion object {
        fun of(value: String?) = Text(value)
        fun of(id: StringResource, vararg formatArgs: Any) = Resource(id, *formatArgs)
        fun empty() = Text("")
    }

    @get:Composable
    val value: String

    fun isEmpty() = this is Text && this.message.isNullOrEmpty()
    fun isNotEmpty() = !isEmpty()


    class Resource(val id: StringResource, vararg val formatArgs: Any) : UiText {

        override val value: String
            @Composable get() = stringResource(resource = id, formatArgs = formatArgs)
    }

    class Text(val message: String?) : UiText {
        override val value: String
            @Composable get() = message ?: ""
    }

    private class Combined(val parts: List<UiText>) : UiText {
        override val value: String
            @Composable get() {
                var result = ""
                parts.forEach {
                    result += it.value
                }
                return result
            }
    }


    operator fun plus(other: UiText): UiText = when {
        this is Combined && other is Combined -> Combined(this.parts + other.parts)
        this is Combined -> Combined(this.parts + other)
        other is Combined -> Combined(listOf(this) + other.parts)
        else -> Combined(listOf(this, other))
    }
}

