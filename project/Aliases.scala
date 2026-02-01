import sbt.addCommandAlias

object Aliases {

  lazy val all = scalaFmt ++ scalaFix ++ scalaLint ++ gatewayCi ++ wahaCI

  lazy val scalaLint = addCommandAlias("checkLint", "clean; checkFix; checkFmt") ++
    addCommandAlias("runLint", "clean; runFix; runFmt")

  lazy val scalaFmt = addCommandAlias("checkFmt", "scalafmtCheckAll; scalafmtSbtCheck") ++
    addCommandAlias("runFmt", "scalafmtAll; scalafmtSbt")

  lazy val scalaFix = addCommandAlias("checkFix", "scalafixAll --check") ++
    addCommandAlias("runFix", "scalafixAll")

  lazy val gatewayCi = Seq(
    addCommandAlias("gateway-build", "clean; project gateway-core; Docker/publishLocal"),
//    addCommandAlias("gateway-build", "clean; project backend; checkLint; test"),
    addCommandAlias("gateway-publish", "clean; project gateway-core; Docker/publish"),
  ).flatten

  lazy val wahaCI = addCommandAlias("waha-build", "clean; project waha; checkLint; test")
}
