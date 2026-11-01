package com.hmodule.controls

import org.junit.Assert.*
import org.junit.Test

class QualityToastScopeTest {
    @Test fun onlyCallbackScopeHidesTipsAndNestedScopesRestoreTheirParent() {
        val scope = QualityToastScope()
        assertFalse(scope.active)
        assertEquals("callback result", scope.during {
            assertTrue(scope.active)
            scope.during { assertTrue(scope.active) }
            assertTrue(scope.active)
            "callback result"
        })
        assertFalse(scope.active)
    }

    @Test fun callbackFailureDoesNotLeakSuppressionToLaterToasts() {
        val scope = QualityToastScope()
        assertThrows(IllegalStateException::class.java) {
            scope.during { error("callback failed") }
        }
        assertFalse(scope.active)
        scope.during {
            assertThrows(IllegalStateException::class.java) { scope.during { error("nested failure") } }
            assertTrue(scope.active)
        }
        assertFalse(scope.active)
    }

    @Test fun unrelatedThreadToastsRemainVisible() {
        val scope = QualityToastScope()
        var otherThreadSuppressed = true
        scope.during {
            Thread { otherThreadSuppressed = scope.active }.also { it.start(); it.join() }
            assertTrue(scope.active)
        }
        assertFalse(otherThreadSuppressed)
        assertFalse(scope.active)
    }
}
