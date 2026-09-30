package com.silentpulse.messenger.feature.settingsbackup

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.Type

internal object StrictSettingsScalars : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (annotations.isNotEmpty()) return null
        val token = when (type) {
            String::class.java -> JsonReader.Token.STRING
            Int::class.javaPrimitiveType, Int::class.javaObjectType,
            Long::class.javaPrimitiveType, Long::class.javaObjectType,
            Float::class.javaPrimitiveType, Float::class.javaObjectType,
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> JsonReader.Token.NUMBER
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> JsonReader.Token.BOOLEAN
            else -> return null
        }
        val delegate = moshi.nextAdapter<Any>(this, type, annotations)
        return object : JsonAdapter<Any>() {
            override fun fromJson(reader: JsonReader): Any? {
                if (reader.peek() != token) throw JsonDataException("Invalid settings field type")
                return delegate.fromJson(reader)
            }
            override fun toJson(writer: JsonWriter, value: Any?) = delegate.toJson(writer, value)
        }.nullSafe()
    }
}
