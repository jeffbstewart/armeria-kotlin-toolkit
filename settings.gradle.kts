rootProject.name = "armeria-kotlin-toolkit"

include("armeria-kotlin-toolkit-auth")

// The auth bridge depends on the sibling auth-kotlin-toolkit. Composite
// build when it's checked out beside this repo (the family convention);
// otherwise the dependency resolves from mavenLocal.
if (file("../auth-kotlin-toolkit").exists()) {
    includeBuild("../auth-kotlin-toolkit")
}
