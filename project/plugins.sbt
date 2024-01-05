resolvers ++= Resolver.sonatypeOssRepos("snapshots")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.15.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")
// TODO: Update when mdoc 2.5.2 is released
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.5.1+14-bd750aad-SNAPSHOT")
