lazy val commonSettings = Seq(
  githubProject := "cedi-circuitbreaker",
  contributors ++= Seq(
    Contributor("sbuzzard", "Steve Buzzard")
  )
)

lazy val root = project.in(file(".")).aggregate(core).settings(commonSettings).settings(noPublish)

lazy val core = project.in(file("core")).enablePlugins(SbtOsgi).
  settings(commonSettings).
  settings(
    name := "circuitbreaker",
    libraryDependencies ++= Seq(
      "com.ccadllc.cedi" %% "config" % "1.1.0",
      "co.fs2" %% "fs2-core" % "0.9.2",
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.circuitbreaker")
  )

lazy val readme = project.in(file("readme")).settings(commonSettings).settings(noPublish).settings(
  tutSettings,
  tutTargetDirectory := baseDirectory.value / ".."
).dependsOn(core)
