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
      "co.fs2" %% "fs2-core" % "1.0.0",
      "org.scalatest" %% "scalatest" % "3.0.5" % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.circuitbreaker"),
    scalacOptions ++= (if (scalaBinaryVersion.value startsWith "2.11") List("-Xexperimental") else Nil) // 2.11 needs -Xexperimental to enable SAM conversion
  )

lazy val readme = project.in(file("readme")).settings(commonSettings).settings(noPublish).enablePlugins(TutPlugin).settings(
  tutTargetDirectory := baseDirectory.value / ".."
).dependsOn(core)
