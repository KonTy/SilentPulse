/*
 * Copyright 2016 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Inlined from io.realm:android-adapters (no longer published to Maven Central).
 */
package io.realm

import androidx.recyclerview.widget.RecyclerView

/**
 * The RealmRecyclerViewAdapter class is an abstract utility class for binding RecyclerView UI
 * elements to Realm data.
 *
 * This adapter will automatically handle any updates to its data and call
 * [notifyDataSetChanged] as appropriate.
 *
 * @param T type of [RealmModel] stored in the adapter.
 * @param VH type of RecyclerView.ViewHolder used in the adapter.
 * @param data collection data to be used by this adapter.
 * @param autoUpdate when it is `true`, the adapter will automatically update when the Realm data changes.
 */
abstract class RealmRecyclerViewAdapter<T : RealmModel, VH : RecyclerView.ViewHolder>(
    private var adapterData: OrderedRealmCollection<T>?,
    private val autoUpdate: Boolean
) : RecyclerView.Adapter<VH>() {

    private val listener: OrderedRealmCollectionChangeListener<OrderedRealmCollection<T>> =
        OrderedRealmCollectionChangeListener { _, _ ->
            notifyDataSetChanged()
        }

    init {
        if (autoUpdate && adapterData != null) {
            addRealmChangeListener(adapterData!!)
        }
    }

    /**
     * Returns data associated with this adapter.
     */
    fun getData(): OrderedRealmCollection<T>? = adapterData

    /**
     * Returns the data associated with the specified position.
     */
    open fun getItem(index: Int): T? {
        val data = adapterData ?: return null
        return data[index]
    }

    override fun getItemCount(): Int {
        val data = adapterData ?: return 0
        return if (data.isValid) data.size else 0
    }

    /**
     * Updates the data associated to the Adapter. Useful when the query has been changed.
     * If the query does not change you might consider using the automaticUpdate feature.
     *
     * @param data the new [OrderedRealmCollection] to display.
     */
    open fun updateData(data: OrderedRealmCollection<T>?) {
        if (autoUpdate && adapterData != null) {
            removeRealmChangeListener(adapterData!!)
        }
        this.adapterData = data
        if (autoUpdate && data != null) {
            addRealmChangeListener(data)
        }
        notifyDataSetChanged()
    }

    @Suppress("UNCHECKED_CAST")
    private fun addRealmChangeListener(data: OrderedRealmCollection<T>) {
        when (data) {
            is RealmResults<T> -> data.addChangeListener(listener as OrderedRealmCollectionChangeListener<RealmResults<T>>)
            is RealmList<T> -> data.addChangeListener(listener as OrderedRealmCollectionChangeListener<RealmList<T>>)
            else -> throw IllegalArgumentException("RealmCollection not supported: " + data.javaClass)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeRealmChangeListener(data: OrderedRealmCollection<T>) {
        when (data) {
            is RealmResults<T> -> data.removeChangeListener(listener as OrderedRealmCollectionChangeListener<RealmResults<T>>)
            is RealmList<T> -> data.removeChangeListener(listener as OrderedRealmCollectionChangeListener<RealmList<T>>)
            else -> throw IllegalArgumentException("RealmCollection not supported: " + data.javaClass)
        }
    }
}
