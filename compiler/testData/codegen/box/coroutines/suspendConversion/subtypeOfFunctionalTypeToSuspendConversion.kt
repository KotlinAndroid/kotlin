// !LANGUAGE: +SuspendConversion
// WITH_RUNTIME
// WITH_COROUTINES
// IGNORE_BACKEND: JVM, NATIVE, JS, JS_IR
// IGNORE_BACKEND_FIR: JVM_IR
// IGNORE_LIGHT_ANALYSIS

import helpers.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

suspend fun useSuspendFun(fn : suspend () -> String) = fn()
suspend fun useSuspendFunInt(fn: suspend (Int) -> String) = fn(42)

class Test : () -> String, (Int) -> String {
    override fun invoke(): String = "OKEmpty"
    override fun invoke(p: Int) = "OK$p"
}

fun box(): String {
    var test = "Failed"
    builder {
        test = useSuspendFun(Test())
    }

    if (test != "OKEmpty") return "failed 1"

    builder {
        test = useSuspendFunInt(Test())
    }

    if (test != "OK42") return "failed 2"

    return "OK"
}