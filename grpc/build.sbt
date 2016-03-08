//protobuf
import com.trueaccord.scalapb.{ScalaPbPlugin => PB}
import sbtassembly.AssemblyPlugin.autoImport._

PB.protobufSettings

PB.runProtoc in PB.protobufConfig := (args =>
  com.github.os72.protocjar.Protoc.runProtoc("-v300" +: args.toArray))

version in PB.protobufConfig := "3.0.0-beta-2"

libraryDependencies ++= Seq(
  "io.grpc" % "grpc-all" % "0.13.1",
  "com.trueaccord.scalapb" %% "scalapb-runtime-grpc" % (PB.scalapbVersion in PB.protobufConfig).value
)

//assembly
assemblyShadeRules in assembly := Seq(
  ShadeRule.rename("io.netty.**" -> "shaded.io.netty.@1").inAll,
  ShadeRule.rename("com.google.guava.**" -> "shaded.io.guava.@1").inAll
)

assemblyOption in assembly :=
  (assemblyOption in assembly).value.copy(includeScala = false)

assemblyOutputPath in assembly := baseDirectory.value / ".." / "lib" / "grpc-assembly-0.1-SNAPSHOT.jar"

assemblyMergeStrategy in assembly := {
  case PathList("javax", "servlet", xs @ _*) => MergeStrategy.first
  case PathList(ps @ _*) if ps.last endsWith ".html" => MergeStrategy.first
  case n if n.startsWith("reference.conf") => MergeStrategy.concat
  case n if n.endsWith(".conf") => MergeStrategy.concat
  case x => MergeStrategy.first
}