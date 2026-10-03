package com.jiligulu.app.ui.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Bounded decimal arithmetic; no script engine and no binary floating-point money. */
object DecimalCalculator {
    data class Result(val value: BigDecimal? = null, val error: String? = null) {
        val display: String? get() = value?.setScale(12, RoundingMode.HALF_UP)
            ?.stripTrailingZeros()?.toPlainString()
        /** A bill stores cents. Rounding is visible beside the use button before submission. */
        val amountText: String? get() = value?.let { number ->
            val cents = runCatching { number.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }
                .getOrNull()?.takeIf { it > 0 } ?: return@let null
            BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString()
        }
    }

    fun evaluate(expression: String): Result {
        if (expression.isBlank()) return Result()
        if (expression.length > 160) return Result(error = "算式有点长，分两次算吧～")
        return try {
            Result(value = Parser(expression).parse())
        } catch (error: CalculationError) {
            Result(error = error.message)
        } catch (_: ArithmeticException) {
            Result(error = "数字太大啦，换个小一点的算式吧")
        }
    }

    private class CalculationError(message: String) : IllegalArgumentException(message)

    private class Parser(private val source: String) {
        private var cursor = 0
        private var operations = 0
        private val divisionContext = MathContext(34, RoundingMode.HALF_UP)

        fun parse(): BigDecimal {
            val result = expression(0)
            skipSpaces()
            if (cursor != source.length) fail("这里的符号还没接好哦")
            return bounded(result)
        }

        private fun expression(depth: Int): BigDecimal {
            var result = term(depth)
            while (true) {
                skipSpaces()
                result = when (peek()) {
                    '+' -> { cursor++; operation(); bounded(result.add(term(depth))) }
                    '-', '−' -> { cursor++; operation(); bounded(result.subtract(term(depth))) }
                    else -> return result
                }
            }
        }

        private fun term(depth: Int): BigDecimal {
            var result = unary(depth)
            while (true) {
                skipSpaces()
                result = when (peek()) {
                    '*', '×' -> { cursor++; operation(); bounded(result.multiply(unary(depth))) }
                    '/', '÷' -> {
                        cursor++; operation()
                        val divisor = unary(depth)
                        if (divisor.compareTo(BigDecimal.ZERO) == 0) fail("不能除以 0 哦")
                        bounded(result.divide(divisor, divisionContext))
                    }
                    else -> return result
                }
            }
        }

        private fun unary(depth: Int): BigDecimal {
            if (depth > 20) fail("括号有点多，先算一小段吧～")
            skipSpaces()
            return when (peek()) {
                '+' -> { cursor++; unary(depth + 1) }
                '-', '−' -> { cursor++; unary(depth + 1).negate() }
                else -> primary(depth)
            }
        }

        private fun primary(depth: Int): BigDecimal {
            skipSpaces()
            if (peek() == '(' || peek() == '（') {
                cursor++
                val value = expression(depth + 1)
                skipSpaces()
                if (peek() != ')' && peek() != '）') fail("再补上一个右括号就好啦")
                cursor++
                return value
            }
            val start = cursor
            var dots = 0
            var digits = 0
            while (cursor < source.length) {
                val char = source[cursor]
                if (char in '0'..'9') { cursor++; digits++ }
                else if (char == '.' && dots == 0) { cursor++; dots++ }
                else break
            }
            if (digits == 0) fail("继续输入，阿噜帮你算～")
            if (digits > 64) fail("数字太长啦，换个小一点的吧")
            return bounded(BigDecimal(source.substring(start, cursor)))
        }

        private fun operation() { if (++operations > 80) fail("算式有点长，分两次算吧～") }
        private fun bounded(value: BigDecimal): BigDecimal {
            if (value.precision() > 100 || value.scale() > 100) fail("数字太大啦，分两次算吧～")
            return value
        }
        private fun peek(): Char? = source.getOrNull(cursor)
        private fun skipSpaces() { while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++ }
        private fun fail(message: String): Nothing = throw CalculationError(message)
    }
}
