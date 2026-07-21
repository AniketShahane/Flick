package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonTest {
    @Test fun rejectsSemanticEscapedDuplicateAndTrailingData() {
        assertNull(StrictJson.objectOnly("{\"t\":\"ping\",\"\\u0074\":\"pong\"}"))
        assertNull(StrictJson.objectOnly("{\"t\":\"ping\"} {}"))
        assertNull(StrictJson.objectOnly("[\"not-an-object\"]"))
    }

    @Test fun preservesNumericTokenTypeInsteadOfCoercing() {
        val objectValue=StrictJson.objectOnly("{\"v\":2,\"fraction\":2.0,\"string\":\"2\"}")!!
        assertEquals(2L, objectValue.integer("v"))
        assertNull(objectValue.integer("fraction"))
        assertNull(objectValue.integer("string"))
        assertTrue(objectValue.number("fraction") == 2.0)
    }
}
