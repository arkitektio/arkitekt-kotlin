rootProject.name = "arkitekt-kotlin"

// The processor that writes an ActionDeclaration for every @Action function (applied with KSP).
include(":arkitekt-kotlin-ksp")
project(":arkitekt-kotlin-ksp").projectDir = file("ksp")

// arkitekt-meshd for every platform, as resources: what Meshd runs to reach the mesh.
include(":arkitekt-kotlin-meshd")
project(":arkitekt-kotlin-meshd").projectDir = file("meshd")
