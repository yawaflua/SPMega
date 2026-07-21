plugins {
    id("dev.kikugie.loom-back-compat")
    id("com.modrinth.minotaur")
    `maven-publish`
}

version = "${property("mod_version")}+${sc.current.version}"
group = property("maven_group") as String

base {
    archivesName = property("archives_base_name") as String
}

val requiredJava = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21
val fabricVersion: String = sc.properties["dependencies.fabric_version"]
val clothConfigVersion: String = sc.properties["dependencies.cloth_config_version"]
val modMenuVersion: String = sc.properties["dependencies.modmenu_version"]
val minecraftCompat: String = sc.properties["metadata.minecraft_compat"]
val loaderCompat: String = sc.properties["metadata.loader_compat"]
val modrinthGameVersions = when (sc.current.project) {
    "1.21.1" -> listOf("1.21.1")
    "1.21.11" -> listOf("1.21.11")
    "26.1.x" -> listOf("26.1", "26.1.1", "26.1.2")
    "26.2.x" -> listOf("26.2")
    else -> error("Missing Modrinth game versions for ${sc.current.project}")
}

loom {
    splitEnvironmentSourceSets()
    interfaceInjection {
        enableDependencyInterfaceInjection = false
    }

    mods {
        create("spmega") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets["client"])
        }
    }
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    maven("https://maven.shedaniel.me/")
    exclusiveContent {
        forRepository {
            maven("https://api.modrinth.com/maven") { name = "Modrinth" }
        }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    if (sc.current.parsed >= "26.1") {
        modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    } else {
        listOf(
            "fabric-api-base",
            "fabric-data-generation-api-v1",
            "fabric-events-interaction-v0",
            "fabric-key-binding-api-v1",
            "fabric-lifecycle-events-v1",
            "fabric-message-api-v1",
            "fabric-networking-api-v1",
            "fabric-rendering-v1",
            "fabric-screen-api-v1"
        ).forEach { module ->
            modImplementation(fabricApi.module(module, fabricVersion))
        }
    }

    modImplementation("me.shedaniel.cloth:cloth-config-fabric:$clothConfigVersion") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    modCompileOnly("maven.modrinth:mOgUt4GM:$modMenuVersion")

    val sqlite = "org.xerial:sqlite-jdbc:${property("sqlite_version")}"
    val zxingCore = "com.google.zxing:core:${property("zxing_version")}"
    val zxingJavase = "com.google.zxing:javase:${property("zxing_version")}"
    implementation(sqlite)
    include(sqlite)
    add("clientImplementation", zxingCore)
    add("clientImplementation", zxingJavase)
    include(zxingCore)
    include(zxingJavase)
}

tasks {
    processResources {
        val values: Map<String, String> = mapOf(
            "version" to project.version.toString(),
            "minecraft_version" to minecraftCompat,
            "loader_version" to loaderCompat,
            "java_version" to requiredJava.majorVersion,
            "cloth_config_version" to clothConfigVersion
        )
        values.forEach(inputs::property)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") { expand(values) }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = requiredJava.majorVersion.toInt()
    }

    jar {
        from(rootProject.file("LICENSE.txt")) {
            rename { "${it}_${project.property("archives_base_name")}" }
        }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds this Minecraft version and collects its distributable JAR"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/${project.property("mod_version")}"))
    }
}

java {
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = property("archives_base_name") as String
            from(components["java"])
        }
    }
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
    versionNumber.set(project.version.toString())
    versionName.set("SPMega ${project.version}")
    versionType.set(property("modrinth_version_type") as String)
    uploadFile.set(loomx.modJar)
    gameVersions.addAll(modrinthGameVersions)
    loaders.add("fabric")
    dependencies {
        required.project("fabric-api")
        optional.project("cloth-config")
        optional.project("modmenu")
    }
}
