lazy val commonSettings = Seq(
  githubProject := "cedi-circuitbreaker",
  crossScalaVersions := Seq("2.13.1", "2.12.10", "2.11.12"),
  scalacOptions --= Seq("-Ywarn-unused-import", "-Xfuture"),
  scalacOptions ++= Seq("-language:higherKinds") ++ (CrossVersion.partialVersion(scalaBinaryVersion.value) match {
     case Some((2, v)) if v <= 12 => Seq("-Xfuture", "-Ywarn-unused-import", "-Ypartial-unification", "-Yno-adapted-args")
     case _ => Seq.empty
  }),
  scalacOptions in (Compile, console) ~= (_ filterNot Set("-Xfatal-warnings", "-Ywarn-unused-import").contains),
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
      "com.ccadllc.cedi" %% "config" % "1.2.0-SNAPSHOT",
      "co.fs2" %% "fs2-core" % "2.0.1",
      "org.scalatest" %% "scalatest" % "3.1.0-RC3" % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.circuitbreaker"),
    scalacOptions ++= (if (scalaBinaryVersion.value startsWith "2.11") List("-Xexperimental") else Nil) // 2.11 needs -Xexperimental to enable SAM conversion
  )

lazy val readme = project.in(file("readme")).settings(commonSettings).settings(noPublish).enablePlugins(TutPlugin).settings(
  tutTargetDirectory := baseDirectory.value / ".."
).dependsOn(core)
