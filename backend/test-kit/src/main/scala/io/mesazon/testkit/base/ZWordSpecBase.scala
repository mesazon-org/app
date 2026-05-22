package io.mesazon.testkit.base

import org.scalamock.stubs.ZIOStubs

open class ZWordSpecBase extends WordSpecBase, ZIOStubs, ZIOTestOps
