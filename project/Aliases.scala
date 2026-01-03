import sbt.addCommandAlias

object Aliases {

  lazy val all = scalaFmt ++ scalaFix ++ scalaLint ++ gatewayPrBuild ++ wahaPrBuild

  lazy val scalaLint = addCommandAlias("checkLint", "clean; checkFix; checkFmt") ++
    addCommandAlias("runLint", "clean; runFix; runFmt")

  lazy val scalaFmt = addCommandAlias("checkFmt", "scalafmtCheckAll; scalafmtSbtCheck") ++
    addCommandAlias("runFmt", "scalafmtAll; scalafmtSbt")

  lazy val scalaFix = addCommandAlias("checkFix", "scalafixAll --check") ++
    addCommandAlias("runFix", "scalafixAll")

  lazy val gatewayPrBuild = addCommandAlias("gateway-pr-build", "clean; checkLint; test")

  lazy val wahaPrBuild = addCommandAlias("waha-pr-build", "project waha; clean; checkLint; test")
}
