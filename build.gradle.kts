plugins { java }
group = "studios.rte"
version = "0.1.0-beta"
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
dependencies { compileOnly("io.papermc.paper:paper-api:26.3.build.+") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
tasks.jar { archiveFileName.set("RTE-Cinema-${project.version}.jar") }
