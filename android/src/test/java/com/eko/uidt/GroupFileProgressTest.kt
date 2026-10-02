package com.eko.uidt

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupFileProgressTest {
    @Test fun `sixty completed files out of one hundred show sixty percent with unknown sizes`() {
        val group = GroupProgress()
        repeat(100) { group.register("file-$it", -1) }
        repeat(60) { group.markCompleted("file-$it") }
        assertEquals(40, group.pendingFiles)
        assertEquals(60, group.progressPercent)
    }

    @Test fun `each file has equal weight regardless of size`() {
        val group = GroupProgress()
        group.register("small", 10)
        group.register("large", 1000)
        group.markCompleted("small")
        assertEquals(50, group.progressPercent)
        group.update("large", 500, 1000)
        assertEquals(75, group.progressPercent)
    }

    @Test fun `queued unknown size does not hide another files partial progress`() {
        val group = GroupProgress()
        group.register("running", 100)
        group.register("queued", -1)
        group.update("running", 80, 100)
        assertEquals(40, group.progressPercent)
    }

    @Test fun `completing unknown size files reaches one hundred percent`() {
        val group = GroupProgress()
        repeat(5) { group.register("file-$it", -1) }
        repeat(5) {
            group.markCompleted("file-$it")
            assertEquals((it + 1) * 20, group.progressPercent)
        }
    }

    @Test fun `individual byte progress is clamped before averaging`() {
        val group = GroupProgress()
        group.register("a", 10)
        group.register("b", 10)
        group.update("a", 20, 10)
        group.update("b", -5, 10)
        assertEquals(50, group.progressPercent)
    }
}
