package com.moez.QKSMS.util

import io.reactivex.Observable

/**
 * A generic interface for a preference value.
 */
interface Preference<T> {
    fun get(): T
    fun set(value: T)
    fun delete()
    fun isSet(): Boolean
    fun asObservable(): Observable<T>
}

/**
 * Preferences class holding all app preferences as [Preference] wrappers.
 * This is a stub implementation for testing and interface purposes.
 */
class Preferences {
    val signature: Preference<String> = object : Preference<String> {
        private var value: String = ""
        override fun get() = value
        override fun set(value: String) { this.value = value }
        override fun delete() { value = "" }
        override fun isSet() = value.isNotEmpty()
        override fun asObservable(): Observable<String> = Observable.just(value)
    }

    val unicode: Preference<Boolean> = object : Preference<Boolean> {
        private var value: Boolean = false
        override fun get() = value
        override fun set(value: Boolean) { this.value = value }
        override fun delete() { value = false }
        override fun isSet() = true
        override fun asObservable(): Observable<Boolean> = Observable.just(value)
    }

    val longAsMms: Preference<Boolean> = object : Preference<Boolean> {
        private var value: Boolean = false
        override fun get() = value
        override fun set(value: Boolean) { this.value = value }
        override fun delete() { value = false }
        override fun isSet() = true
        override fun asObservable(): Observable<Boolean> = Observable.just(value)
    }

    val delivery: Preference<Boolean> = object : Preference<Boolean> {
        private var value: Boolean = false
        override fun get() = value
        override fun set(value: Boolean) { this.value = value }
        override fun delete() { value = false }
        override fun isSet() = true
        override fun asObservable(): Observable<Boolean> = Observable.just(value)
    }

    val canUseSubId: Preference<Boolean> = object : Preference<Boolean> {
        private var value: Boolean = true
        override fun get() = value
        override fun set(value: Boolean) { this.value = value }
        override fun delete() { value = true }
        override fun isSet() = true
        override fun asObservable(): Observable<Boolean> = Observable.just(value)
    }

    val mmsSize: Preference<Int> = object : Preference<Int> {
        private var value: Int = -1
        override fun get() = value
        override fun set(value: Int) { this.value = value }
        override fun delete() { value = -1 }
        override fun isSet() = true
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }
}
