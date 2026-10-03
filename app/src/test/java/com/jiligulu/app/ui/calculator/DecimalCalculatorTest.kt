package com.jiligulu.app.ui.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DecimalCalculatorTest {
    @Test fun decimalMoneyDoesNotAccumulateBinaryRoundingErrors() {
        assertEquals("0.3", DecimalCalculator.evaluate("0.1 + 0.2").display)
        assertEquals("0.3", DecimalCalculator.evaluate("0.1 + 0.2").amountText)
        assertEquals("0", DecimalCalculator.evaluate("0.3 - 0.2 - 0.1").display)
    }

    @Test fun multiplicationPrecedenceAndNestedParenthesesWork() {
        assertEquals("30", DecimalCalculator.evaluate("12 + 9 × 2").display)
        assertEquals("42", DecimalCalculator.evaluate("(12 + 9) × 2").display)
        assertEquals("5", DecimalCalculator.evaluate("（10 − (3 + 2)）").display)
        assertEquals("6", DecimalCalculator.evaluate("-2 × -3").display)
    }

    @Test fun centsAreRoundedOnlyForTheBillTransfer() {
        assertEquals("0.333333333333", DecimalCalculator.evaluate("1 ÷ 3").display)
        assertEquals("0.33", DecimalCalculator.evaluate("1 ÷ 3").amountText)
        assertEquals("1.01", DecimalCalculator.evaluate("1.005").amountText)
        assertNull(DecimalCalculator.evaluate("0.004").amountText)
        assertEquals("0.01", DecimalCalculator.evaluate("0.005").amountText)
        assertEquals("1", DecimalCalculator.evaluate("1.00499999999999").amountText)
    }

    @Test fun negativeAndOverflowResultsCannotBecomeBills() {
        assertEquals("-3", DecimalCalculator.evaluate("2 - 5").display)
        assertNull(DecimalCalculator.evaluate("2 - 5").amountText)
        assertNull(DecimalCalculator.evaluate("92233720368547758.08").amountText)
        assertEquals("92233720368547758.07", DecimalCalculator.evaluate("92233720368547758.07").amountText)
    }

    @Test fun divisionByZeroAndIncompleteExpressionsRemainEditable() {
        assertEquals("不能除以 0 哦", DecimalCalculator.evaluate("3 ÷ (1 - 1)").error)
        assertNull(DecimalCalculator.evaluate("3 +").amountText)
        assertNotNull(DecimalCalculator.evaluate("(3 + 4").error)
        assertNull(DecimalCalculator.evaluate("").value)
    }

    @Test fun parserRejectsScriptsUnexpectedCharactersAndExcessiveWork() {
        assertNull(DecimalCalculator.evaluate("1; exit()").value)
        assertNull(DecimalCalculator.evaluate("2e5").value)
        assertNull(DecimalCalculator.evaluate("9".repeat(65)).value)
        assertNull(DecimalCalculator.evaluate("(".repeat(25) + "1" + ")".repeat(25)).value)
        assertNull(DecimalCalculator.evaluate("1+".repeat(81) + "1").value)
    }
}
