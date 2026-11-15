rootProject.name = "arkitekt-kotlin"

// The processor that writes an ActionDeclaration for every @Action function (applied with KSP).
include(":arkitekt-kotlin-ksp")
project(":arkitekt-kotlin-ksp").projectDir = file("ksp")
