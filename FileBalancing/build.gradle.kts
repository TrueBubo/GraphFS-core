plugins {
    kotlin("jvm") version "2.4.20"
}

group = "eu.secondfoundation.interview"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kandy-lets-plot:0.8.6")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(26)
}

tasks.test {
    useJUnitPlatform()
}

/** ./gradlew scaling -Pfiles=1000000 -Pevents=100000000: one run of the scaling benchmark. */
tasks.register<JavaExec>("scaling") {
    mainClass = "ScalingKt"
    classpath = sourceSets.main.get().runtimeClasspath
    maxHeapSize = (findProperty("heap") ?: "16g").toString()
    args((findProperty("files") ?: "100000").toString(), (findProperty("events") ?: "10000000").toString(),
        layout.buildDirectory.file("scaling/scaling.csv").get().asFile.path)
}

/** ./gradlew wikiPrep: data/wikipedia/clickstream-*.tsv.gz -> links.bin and titles.txt (download it first). */
tasks.register<JavaExec>("wikiPrep") {
    mainClass = "WikiPrepKt"
    classpath = sourceSets.main.get().runtimeClasspath
    maxHeapSize = "8g"
    val dir = layout.projectDirectory.dir("data/wikipedia").asFile
    args(dir.listFiles { f -> f.name.matches(Regex("clickstream-.*\\.tsv\\.gz")) }?.maxOrNull()?.path ?: "missing", dir.path)
}

/** ./gradlew wiki -Ptraversals=50000000 -PmaxFiles=100000 [-Ppolicies=weight,LRU,...]: the Wikipedia simulation. */
tasks.register<JavaExec>("wiki") {
    mainClass = "WikiSimulationKt"
    classpath = sourceSets.main.get().runtimeClasspath
    maxHeapSize = "24g"
    args(layout.projectDirectory.dir("data/wikipedia").asFile.path,
        (findProperty("traversals") ?: "50000000").toString(), (findProperty("maxFiles") ?: "100000").toString(),
        *listOfNotNull(findProperty("policies")?.toString()).toTypedArray())
}

/** ./gradlew twitter -PmaxFiles=10000 -PhalfLife=20000 [-Ppolicies=weight,LRU,...]: Higgs Twitter replay (download data/twitter/higgs-activity_time.txt.gz from SNAP first). */
tasks.register<JavaExec>("twitter") {
    mainClass = "TwitterSimulationKt"
    classpath = sourceSets.main.get().runtimeClasspath
    maxHeapSize = "8g"
    args(layout.projectDirectory.file("data/twitter/higgs-activity_time.txt.gz").asFile.path,
        (findProperty("maxFiles") ?: "10000").toString(), (findProperty("halfLife") ?: "20000").toString(),
        *listOfNotNull(findProperty("policies")?.toString()).toTypedArray())
}

/** ./gradlew linuxLog: the commit log of data/linux/linux.git (git clone --bare --filter=blob:none https://github.com/torvalds/linux.git) as data/linux/log.txt.gz. */
tasks.register<Exec>("linuxLog") {
    val dir = layout.projectDirectory.dir("data/linux").asFile
    commandLine("sh", "-c", "git -C linux.git log --reverse --no-merges --no-renames --name-only --format='commit %ct' | gzip > log.txt.gz")
    workingDir = dir
}

/** ./gradlew linux -PmaxFiles=10000 -PhalfLife=100000 [-Ppolicies=weight,LRU,...]: Linux co-change replay (./gradlew linuxLog first). */
tasks.register<JavaExec>("linux") {
    mainClass = "LinuxSimulationKt"
    classpath = sourceSets.main.get().runtimeClasspath
    maxHeapSize = "8g"
    args(layout.projectDirectory.file("data/linux/log.txt.gz").asFile.path,
        (findProperty("maxFiles") ?: "10000").toString(), (findProperty("halfLife") ?: "100000").toString(),
        *listOfNotNull(findProperty("policies")?.toString()).toTypedArray())
}

/** ./gradlew plotCaches: charts of the cache-size sweeps (csv files of the Twitter and Wikipedia runs) into docs/caches/. */
tasks.register<JavaExec>("plotCaches") {
    mainClass = "CachePlotKt"
    classpath = sourceSets.main.get().runtimeClasspath
    val data = layout.projectDirectory.dir("data")
    args(listOf(layout.projectDirectory.dir("docs/caches").asFile.path) +
        listOf("twitter/sweep.csv", "wikipedia/sweep.csv", "linux/sweep.csv").map { data.file(it).asFile }.filter { it.exists() }.map { it.path })
}

/** ./gradlew distributed -Pdataset=twitter|wiki|linux [-PmaxFiles=...] [-Ppolicy=LRU] [-Pcsv=...] [-Pvariants=a,b]: central vs distributed placement. */
tasks.register<JavaExec>("distributed") {
    mainClass = "DistributedSimulationKt"
    classpath = sourceSets.main.get().runtimeClasspath
    maxHeapSize = "16g"
    val data = layout.projectDirectory.dir("data")
    val dataset = (findProperty("dataset") ?: "twitter").toString()
    val wiki = dataset == "wiki"
    val source = when (dataset) {
        "wiki" -> data.dir("wikipedia").asFile.path
        "linux" -> data.file("linux/log.txt.gz").asFile.path
        else -> data.file("twitter/higgs-activity_time.txt.gz").asFile.path
    }
    args(listOf(if (dataset == "linux") "linux" else if (wiki) "wiki" else "twitter", source) +
        (if (wiki) listOf((findProperty("traversals") ?: "10000000").toString()) else emptyList()) +
        listOf((findProperty("maxFiles") ?: (if (wiki) "100000" else "10000")).toString(), (findProperty("policy") ?: "LRU").toString(),
            (findProperty("csv") ?: "").toString()) + listOfNotNull(findProperty("variants")?.toString()))
}

/** ./gradlew pairMemoryCheck -Pdataset=twitter|linux -Pcounts=exact|cms: the distributed placer's memory estimate against the heap. */
tasks.register<JavaExec>("pairMemoryCheck") {
    mainClass = "PairMemoryCheckKt"
    classpath = sourceSets.main.get().runtimeClasspath
    maxHeapSize = "6g"
    jvmArgs("-XX:+UseParallelGC")
    val dataset = (findProperty("dataset") ?: "twitter").toString()
    val source = if (dataset == "linux") "data/linux/log.txt.gz" else "data/twitter/higgs-activity_time.txt.gz"
    args(dataset, layout.projectDirectory.file(source).asFile.path, (findProperty("counts") ?: "cms").toString())
}

/** ./gradlew plotDistributed: charts of data/distributed*.csv (DistributedSimulation runs with a csv) into docs/distributed/. */
tasks.register<JavaExec>("plotDistributed") {
    mainClass = "DistributedPlotKt"
    classpath = sourceSets.main.get().runtimeClasspath
    val data = layout.projectDirectory.dir("data")
    args(listOf(layout.projectDirectory.dir("docs/distributed").asFile.path) +
        listOf("distributed.csv", "distributed-memory.csv", "distributed-sweep.csv").map { data.file(it).asFile }.filter { it.exists() }.map { it.path })
}
