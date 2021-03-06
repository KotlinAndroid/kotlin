/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class RootsIndexTest {
    @Test
    fun findRoot() {
        val roots = RootsIndex<Int>()
        roots["a/b/c"] = 1
        assertFails { roots["a/b/c/d"] = 2 }
        roots["a/c"] = 3
        roots["d"] = 4

        assertEquals(1, roots.findRoot("a/b/c/x"))
        assertEquals(1, roots.findRoot("a/b/c"))
        assertEquals(3, roots.findRoot("a/c/x"))
        assertEquals(3, roots.findRoot("a/c"))
        assertEquals(4, roots.findRoot("d"))
        assertEquals(4, roots.findRoot("d/x"))
        assertEquals(null, roots.findRoot("e"))
        assertEquals(null, roots.findRoot("e/f"))

        roots["d"] = 5
        assertEquals(5, roots.findRoot("d"))
    }
}