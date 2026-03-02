import sbt.*

object CustomResolvers {

  lazy val resolvers: Seq[Resolver] = Seq(
    "SignalBuildArtifacts" at "https://build-artifacts.signal.org/libraries/maven/"
  )
}
