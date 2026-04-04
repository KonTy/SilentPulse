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
    
    companion object {
        const val BLOCKING_MANAGER_CB = 1
        const val BLOCKING_MANAGER_SIA = 2
        const val BLOCKING_MANAGER_CC = 3
        const val BLOCKING_MANAGER_QKSMS = 4
        
        const val NOTIFICATION_ACTION_READ = 1
        const val NOTIFICATION_ACTION_REPLY = 2
        const val NOTIFICATION_ACTION_CALL = 3
        const val NOTIFICATION_ACTION_DELETE = 4
        
        const val SWIPE_ACTION_ARCHIVE = 1
        const val SWIPE_ACTION_DELETE = 2
        const val SWIPE_ACTION_CALL = 3
        const val SWIPE_ACTION_READ = 4
        const val SWIPE_ACTION_UNREAD = 5
    }

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
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Boolean) { this.value = value; this.set = true }
        override fun delete() { value = true; set = false }
        override fun isSet() = set
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

    val version: Preference<Int> = object : Preference<Int> {
        private var value: Int = 0
        override fun get() = value
        override fun set(value: Int) { this.value = value }
        override fun delete() { value = 0 }
        override fun isSet() = true
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }

    val changelogVersion: Preference<Int> = object : Preference<Int> {
        private var value: Int = 0
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Int) { this.value = value; this.set = true }
        override fun delete() { value = 0; set = false }
        override fun isSet() = set
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }

    val blockingManager: Preference<Int> = object : Preference<Int> {
        private var value: Int = BLOCKING_MANAGER_QKSMS
        override fun get() = value
        override fun set(value: Int) { this.value = value }
        override fun delete() { value = BLOCKING_MANAGER_QKSMS }
        override fun isSet() = true
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }

    val sia: Preference<Boolean> = object : Preference<Boolean> {
        private var value: Boolean = false
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Boolean) { this.value = value; this.set = true }
        override fun delete() { set = false }
        override fun isSet() = set
        override fun asObservable(): Observable<Boolean> = Observable.just(value)
    }

    val notifAction1: Preference<Int> = object : Preference<Int> {
        private var value: Int = 0
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Int) { this.value = value; this.set = true }
        override fun delete() { value = 0; set = false }
        override fun isSet() = set
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }

    val notifAction2: Preference<Int> = object : Preference<Int> {
        private var value: Int = 0
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Int) { this.value = value; this.set = true }
        override fun delete() { value = 0; set = false }
        override fun isSet() = set
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }

    val notifAction3: Preference<Int> = object : Preference<Int> {
        private var value: Int = 0
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Int) { this.value = value; this.set = true }
        override fun delete() { value = 0; set = false }
        override fun isSet() = set
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }

    val swipeLeft: Preference<Int> = object : Preference<Int> {
        private var value: Int = 0
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Int) { this.value = value; this.set = true }
        override fun delete() { value = 0; set = false }
        override fun isSet() = set
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }

    val swipeRight: Preference<Int> = object : Preference<Int> {
        private var value: Int = 0
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Int) { this.value = value; this.set = true }
        override fun delete() { value = 0; set = false }
        override fun isSet() = set
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }

    fun theme(id: Long): Preference<Int> = object : Preference<Int> {
        private var value: Int = 0
        private var set: Boolean = false
        override fun get() = value
        override fun set(value: Int) { this.value = value; this.set = true }
        override fun delete() { value = 0; set = false }
        override fun isSet() = set
        override fun asObservable(): Observable<Int> = Observable.just(value)
    }
}
