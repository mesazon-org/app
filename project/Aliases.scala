import sbt.addCommandAlias

object Aliases {

  lazy val all = scalaFmt ++ scalaFix ++ scalaLint ++ gatewayCi ++ wahaCI

  lazy val scalaLint = addCommandAlias("checkLint", "checkFix; checkFmt") ++
    addCommandAlias("runLint", "runFix; runFmt")

  lazy val scalaFmt = addCommandAlias("checkFmt", "scalafmtCheckAll; scalafmtSbtCheck") ++
    addCommandAlias("runFmt", "scalafmtAll; scalafmtSbt")

  lazy val scalaFix = addCommandAlias("checkFix", "scalafixAll --check") ++
    addCommandAlias("runFix", "scalafixAll")

  lazy val gatewayCi = addCommandAlias("gateway-build", "clean; project backend; checkLint; test")

  lazy val wahaCI = addCommandAlias("waha-build", "clean; project waha; checkLint; test")
}
