package com.jiligulu.app.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgIconValidatorTest {
    @Test fun `standard namespace and simple static drawing pass`() {
        assertTrue(SvgIconValidator.isValid("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><g fill="none" stroke="#534AB7"><path d="M2 2 L22 22"/><circle cx="12" cy="12" r="3" /></g></svg>"""))
        assertTrue(SvgIconValidator.isValid("""<svg viewBox="0 0 24 24"><rect width="16" height="12"/></svg>"""))
    }

    @Test fun `namespace allowance does not allow other URLs or namespaces`() {
        listOf(
            """<svg xmlns="http://www.w3.org/2000/svg"><path fill="http://example.test/a"/></svg>""",
            """<svg xmlns="https://www.w3.org/2000/svg"/>""",
            """<svg xmlns="http://www.w3.org/2000/svg/evil"/>""",
            """<svg xmlns:xlink="http://www.w3.org/1999/xlink"/>""",
            """<svg><g xmlns="http://www.w3.org/2000/svg"/></svg>""",
            """<svg><path href="//example.test/a"/></svg>""",
            """<svg><path fill="data:image/svg+xml,abc"/></svg>""",
            """<svg><path fill="url(//example.test/a)"/></svg>""",
            """<svg><use xlink:href="#shape"/></svg>"""
        ).forEach { assertFalse(it, SvgIconValidator.isValid(it)) }
    }

    @Test fun `malformed XML script events and entity bypasses are rejected`() {
        listOf(
            "<svg><script/></svg>", "<svg onload='alert(1)'/>", "<svg><image/></svg>",
            "<svg><g></svg>", "<svg></svg><svg/>", "<svg garbage/>", "<svg",
            """<svg><path fill="h&#116;tp://example.test"/></svg>""",
            "<!DOCTYPE svg><svg/>", "<svg xmlns='http://www.w3.org/2000/svg' xmlns='no'/>"
        ).forEach { assertFalse(it, SvgIconValidator.isValid(it)) }
    }
}
