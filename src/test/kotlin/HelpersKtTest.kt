package com.lightningkite.deployhelpers

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class HelpersKtTest {
    @Test
    fun test() {
        println(File(".").getGitHash())
    }
}