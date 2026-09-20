package com.silentpulse.messenger.common.util.extensions

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class AdapterExtensionsTest {

    private val adapter = mock(RecyclerView.Adapter::class.java)
    private val recyclerView = mock(RecyclerView::class.java)
    private val layoutManager = mock(LinearLayoutManager::class.java)
    private lateinit var observer: RecyclerView.AdapterDataObserver
    private var count = 10
    private var outgoing = false

    @Before
    fun setUp() {
        doAnswer { count }.`when`(adapter).itemCount
        `when`(recyclerView.layoutManager).thenReturn(layoutManager)
        `when`(layoutManager.stackFromEnd).thenReturn(true)
        `when`(layoutManager.findLastVisibleItemPosition()).thenReturn(9)

        adapter.autoScrollToStart(recyclerView) { outgoing }
        val captor = ArgumentCaptor.forClass(RecyclerView.AdapterDataObserver::class.java)
        verify(adapter).registerAdapterDataObserver(captor.capture())
        observer = captor.value
    }

    @Test
    fun `Realm refresh follows a new received message`() {
        count = 11
        observer.onChanged()

        verify(recyclerView).scrollToPosition(10)
    }

    @Test
    fun `Realm refresh follows all messages in a batch`() {
        count = 13
        observer.onChanged()

        verify(recyclerView).scrollToPosition(12)
    }

    @Test
    fun `incoming message preserves older reading position`() {
        `when`(layoutManager.findLastVisibleItemPosition()).thenReturn(4)
        count = 11
        observer.onChanged()

        verify(recyclerView, never()).scrollToPosition(anyInt())
    }

    @Test
    fun `sending a message returns to the newest message from history`() {
        `when`(layoutManager.findLastVisibleItemPosition()).thenReturn(4)
        outgoing = true
        count = 11
        observer.onChanged()

        verify(recyclerView).scrollToPosition(10)
    }

    @Test
    fun `status and search highlight refreshes do not move the list`() {
        outgoing = true
        observer.onChanged()

        verify(recyclerView, never()).scrollToPosition(anyInt())
    }

    @Test
    fun `empty conversation follows its first message`() {
        count = 0
        observer.onChanged()
        `when`(layoutManager.findLastVisibleItemPosition()).thenReturn(RecyclerView.NO_POSITION)
        count = 1
        observer.onChanged()

        verify(recyclerView).scrollToPosition(0)
        verify(recyclerView, never()).scrollToPosition(-1)
    }

    @Test
    fun `range insertion follows the last message in a batch`() {
        count = 13
        observer.onItemRangeInserted(10, 3)

        verify(adapter).notifyItemChanged(9)
        verify(recyclerView).scrollToPosition(12)
    }

    @Test
    fun `inserting older messages does not jump to the bottom`() {
        count = 13
        observer.onItemRangeInserted(0, 3)

        verify(recyclerView, never()).scrollToPosition(anyInt())
    }

    @Test
    fun `removals update the count used by the next full refresh`() {
        count = 8
        observer.onItemRangeRemoved(8, 2)
        `when`(layoutManager.findLastVisibleItemPosition()).thenReturn(7)
        count = 9
        observer.onChanged()

        verify(recyclerView).scrollToPosition(8)
    }

    @Test
    fun `rapid updates keep following before the next layout`() {
        count = 11
        observer.onChanged()
        count = 12
        observer.onChanged()

        verify(recyclerView).scrollToPosition(11)
        val captor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(recyclerView).addOnLayoutChangeListener(captor.capture())
    }

    @Test
    fun `completed scroll does not force later incoming messages while reading history`() {
        count = 11
        observer.onChanged()
        completeLayout()

        `when`(layoutManager.findLastVisibleItemPosition()).thenReturn(4)
        count = 12
        observer.onChanged()

        verify(recyclerView, never()).scrollToPosition(11)
    }

    @Test
    fun `tall message is aligned to the bottom after layout including margins`() {
        count = 11
        observer.onChanged()
        val lastView = mock(View::class.java)
        val params = mock(RecyclerView.LayoutParams::class.java)
        params.bottomMargin = 12
        `when`(lastView.layoutParams).thenReturn(params)
        `when`(layoutManager.findViewByPosition(10)).thenReturn(lastView)
        `when`(layoutManager.getDecoratedBottom(lastView)).thenReturn(950)
        `when`(layoutManager.height).thenReturn(600)
        `when`(layoutManager.paddingBottom).thenReturn(8)

        completeLayout()

        verify(recyclerView).scrollBy(0, 370)
    }

    @Test
    fun `short message does not pull content away from the bottom`() {
        count = 11
        observer.onChanged()
        val lastView = mock(View::class.java)
        `when`(lastView.layoutParams).thenReturn(mock(RecyclerView.LayoutParams::class.java))
        `when`(layoutManager.findViewByPosition(10)).thenReturn(lastView)
        `when`(layoutManager.getDecoratedBottom(lastView)).thenReturn(600)
        `when`(layoutManager.height).thenReturn(600)

        completeLayout()

        verify(recyclerView, never()).scrollBy(anyInt(), anyInt())
    }

    @Test
    fun `top stacked conversation list retains its existing behavior`() {
        `when`(layoutManager.stackFromEnd).thenReturn(false)
        `when`(layoutManager.findFirstVisibleItemPosition()).thenReturn(0)
        count = 11
        observer.onItemRangeInserted(0, 1)

        verify(recyclerView).scrollToPosition(0)
    }

    @Test
    fun `full refresh does not move the top stacked conversation list`() {
        `when`(layoutManager.stackFromEnd).thenReturn(false)
        count = 11
        observer.onChanged()

        verify(recyclerView, never()).scrollToPosition(anyInt())
    }

    private fun completeLayout() {
        val captor = ArgumentCaptor.forClass(View.OnLayoutChangeListener::class.java)
        verify(recyclerView).addOnLayoutChangeListener(captor.capture())
        captor.value.onLayoutChange(recyclerView, 0, 0, 400, 600, 0, 0, 400, 600)
    }
}
