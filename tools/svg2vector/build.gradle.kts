plugins { application; kotlin("jvm") version "2.4.21" }
repositories { google(); mavenCentral() }
dependencies { implementation("com.android.tools:sdk-common:32.5.1") }
application { mainClass.set("ConvKt") }
kotlin { jvmToolchain(21) }
