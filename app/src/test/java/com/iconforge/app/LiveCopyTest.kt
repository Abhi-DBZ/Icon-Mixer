package com.iconforge.app

import com.iconforge.app.export.ApkBuilder
import com.iconforge.app.export.LiveBundle
import com.iconforge.app.export.LiveCopier
import com.iconforge.app.export.PackIcon
import com.iconforge.app.export.PackMapping
import com.iconforge.app.export.PackSpec
import com.iconforge.app.export.PackCalendar
import com.iconforge.app.export.ShapePaths
import com.iconforge.app.export.IconPalette
import com.iconforge.app.export.SourceRes
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Live colours: builds a small "Material You" icon pack with aapt2 (colours pointing at
 * @android:color/system_accent1_*, night variants, a vector and an adaptive icon), copies
 * its icon into an exported pack and checks the result with aapt2. Skipped without the SDK.
 */
class LiveCopyTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun sdkDir(): File? {
        System.getenv("ANDROID_HOME")?.let { File(it) }?.takeIf { it.isDirectory }?.let { return it }
        System.getenv("ANDROID_SDK_ROOT")?.let { File(it) }?.takeIf { it.isDirectory }?.let { return it }
        val lp = File(root, "local.properties").takeIf { it.isFile } ?: return null
        return Properties().apply { lp.inputStream().use { load(it) } }.getProperty("sdk.dir")?.let { File(it) }
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(2, TimeUnit.MINUTES)
        check(p.exitValue() == 0) { "${cmd.joinToString(" ")}\n$out" }
        return out
    }

    private fun write(dir: File, path: String, text: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }

    private val png: ByteArray = java.io.ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()

    /** Optional, local only: reshape every icon of a real pack (set ICONMIXER_REAL_PACK to its APK). */
    @Test
    fun reshapesRealPack() {
        val apk = System.getenv("ICONMIXER_REAL_PACK")?.let { File(it) }
        Assume.assumeTrue("ICONMIXER_REAL_PACK not set", apk?.isFile == true)
        val sdk = sdkDir()!!
        val aapt2 = File(sdk, "build-tools").listFiles()!!.maxBy { it.name }.let { File(it, if (File(it, "aapt2.exe").exists()) "aapt2.exe" else "aapt2") }.path
        val bundle = LiveBundle()
        val maps = ArrayList<PackMapping>()
        var tried = 0; var ok = 0
        SourceRes(listOf(apk!!)).use { src ->
            val copier = LiveCopier(src, "real.pack")
            val drawables = src.names.values.filter { it.first == "drawable" }.map { it.second }.distinct().take(400)
            drawables.forEachIndexed { i, d ->
                tried++
                if (copier.collect(d, "r$i", bundle, shape = ShapePaths.svg("CIRCLE"))) { ok++; maps += PackMapping("p$i.app", "p$i.app.Main", "r$i") }
            }
        }
        println("reshaped $ok of $tried drawables")
        val png = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val out = File(root, "app/build/livetest/real.apk").apply { parentFile.mkdirs() }
        ApkBuilder.buildUnsigned(PackSpec(packageName = "com.iconforge.pack.real", label = "Real", versionCode = 1, versionName = "1",
            icons = listOf(PackIcon("static_icon", png)), mappings = maps, appIconRes = "static_icon", calendars = emptyList(), live = bundle), out)
        val dump = run(aapt2, "dump", "resources", out.path)
        val first = Regex("""(res/lv/\S+\.xml)""").findAll(dump).map { it.value }.first { p ->
            run(aapt2, "dump", "xmltree", "--file", p, out.path).contains("E: layer-list")
        }
        println(run(aapt2, "dump", "xmltree", "--file", first, out.path))
        assertTrue(ok > tried / 2)
    }

    @Test
    fun copiesMaterialYouIcon() {
        val sdk = sdkDir()
        Assume.assumeTrue("Android SDK not found", sdk != null)
        val buildTools = File(sdk, "build-tools").listFiles()?.filter { File(it, "aapt2").exists() || File(it, "aapt2.exe").exists() }
            ?.maxByOrNull { it.name }
        val platform = File(sdk, "platforms").listFiles()?.filter { File(it, "android.jar").isFile && (it.name.removePrefix("android-")
            .substringBefore('.').toIntOrNull() ?: 0) >= 31 }?.maxByOrNull { it.name }
        Assume.assumeTrue("aapt2 / android.jar (31+) not found", buildTools != null && platform != null)
        val aapt2 = File(buildTools, if (File(buildTools, "aapt2.exe").exists()) "aapt2.exe" else "aapt2").path
        val androidJar = File(platform, "android.jar").path

        val work = File(root, "app/build/livetest").apply { deleteRecursively(); mkdirs() }
        val res = File(work, "res")
        write(res, "values/colors.xml", """
            <resources>
                <color name="fg">@android:color/system_accent1_600</color>
                <color name="bg">@android:color/system_accent1_100</color>
            </resources>""".trimIndent())
        write(res, "values-night/colors.xml", """
            <resources>
                <color name="fg">@android:color/system_accent1_200</color>
                <color name="bg">@android:color/system_accent1_800</color>
            </resources>""".trimIndent())
        write(res, "drawable/glyph.xml", """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
                <path android:fillColor="@color/fg" android:pathData="M34,34h40v40h-40z" />
            </vector>""".trimIndent())
        write(res, "drawable-anydpi-v26/camera.xml", """
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@color/bg" />
                <foreground android:drawable="@drawable/glyph" />
            </adaptive-icon>""".trimIndent())
        // Arcticons-style: inline foreground (layer-list > item > inset > vector).
        write(res, "drawable-anydpi-v26/phone.xml", """
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@color/bg" />
                <foreground>
                    <layer-list><item><inset android:inset="25%">
                        <vector android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
                            <path android:fillColor="#00000000" android:strokeColor="@color/fg" android:strokeWidth="1.2" android:pathData="M10,10L38,38" />
                        </vector>
                    </inset></item></layer-list>
                </foreground>
            </adaptive-icon>""".trimIndent())
        // A picture background can't be cut to a shape while staying live.
        write(res, "drawable-anydpi-v26/photo_bg.xml", """
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@drawable/plain" />
                <foreground android:drawable="@drawable/glyph" />
            </adaptive-icon>""".trimIndent())
        File(res, "drawable-nodpi").mkdirs(); File(res, "drawable-nodpi/plain.png").writeBytes(png)
        val manifest = write(work, "AndroidManifest.xml",
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.mypack"><uses-sdk android:minSdkVersion="26" /></manifest>""")
        val compiled = File(work, "compiled.zip").path
        run(aapt2, "compile", "--dir", res.path, "-o", compiled)

        // Plain, sparse and compact resource tables (newer aapt2 / AGP output) must all read.
        val variants = listOf(emptyList(), listOf("--enable-sparse-encoding"), listOf("--enable-compact-entries"))
        variants.forEachIndexed { i, extra ->
            val srcApk = File(work, "src$i.apk")
            run(aapt2, "link", "-I", androidJar, "--manifest", manifest.path, "-o", srcApk.path, *extra.toTypedArray(), compiled)

            val bundle = LiveBundle()
            SourceRes(listOf(srcApk)).use { src ->
                val copier = LiveCopier(src, "com.example.mypack")
                assertTrue("adaptive icon should copy ($extra)", copier.collect("camera", "camera_live", bundle))
                assertFalse("bitmap icons stay rasterised", copier.collect("plain", "plain_live", bundle))
                // With a theme shape, adaptive icons are rewritten already cut to it; plain vectors
                // stay live unless every icon must be shaped.
                val circle = ShapePaths.svg("CIRCLE")
                assertTrue("adaptive reshaped", copier.collect("camera", "camera_round", bundle, shape = circle))
                assertTrue("inline foreground reshaped", copier.collect("phone", "phone_round", bundle, shape = circle))
                assertFalse("picture background stays a picture", copier.collect("photo_bg", "p2", LiveBundle(), shape = circle))
                assertTrue("vector live with a shape", copier.collect("glyph", "g2", LiveBundle(), shape = circle))
                assertFalse("vector refused when every icon is shaped", copier.collect("glyph", "g3", LiveBundle(), shape = circle, shapeAll = true))
                assertTrue("adaptive fine when every icon is shaped", copier.collect("camera", "c3", LiveBundle(), shape = circle, shapeAll = true))
            }
            val spec = PackSpec(
                packageName = "com.iconforge.pack.livetest", label = "Live test", versionCode = 1, versionName = "1",
                icons = listOf(PackIcon("static_icon", png)),
                mappings = listOf(
                    PackMapping("com.android.camera", "com.android.camera.Camera", "camera_live"),
                    PackMapping("com.example.other", "com.example.other.Main", "static_icon"),
                ),
                appIconRes = "static_icon",
                calendars = listOf(PackCalendar("com.google.android.calendar", "com.android.calendar.AllInOneActivity", "cal_x_")),
                live = bundle,
            )
            val out = File(work, "out$i.apk")
            ApkBuilder.buildUnsigned(spec, out)

            val dump = run(aapt2, "dump", "resources", out.path)
            assertTrue(dump, dump.contains("drawable/camera_live"))
            assertTrue(dump, dump.contains("drawable/static_icon"))
            // aapt2 prints framework references by number: 0x0106xxxx = android:color/system_accent*.
            assertTrue("framework colours kept\n$dump", Regex("@0x0106[0-9a-f]{4}").findAll(dump).count() >= 4)
            assertTrue("night variant kept\n$dump", dump.contains("(night) @0x0106"))
            assertTrue("adaptive icon keeps its config\n$dump", dump.contains("(anydpi)"))
            assertTrue("alias points at the copy\n$dump", Regex("""drawable/camera_live\s+\(\) @drawable/lv""").containsMatchIn(dump))
            // The copied adaptive icon's layers must point at copied resources inside the new pack.
            val badging = run(aapt2, "dump", "badging", out.path)
            assertTrue(badging, badging.contains("package: name='com.iconforge.pack.livetest'"))
            val adaptive = Regex("""(res/lv/\S+\.xml)""").findAll(dump).map { it.value }.toList()
            assertTrue(dump, adaptive.isNotEmpty())
            var sawAdaptive = false
            var shaped = 0
            adaptive.distinct().forEach { path ->
                val tree = run(aapt2, "dump", "xmltree", "--file", path, out.path)
                assertFalse("no dangling refs to the source pack\n$tree", tree.contains("@0x7f") && !tree.contains("@0x7f0"))
                if (tree.contains("adaptive-icon")) {
                    sawAdaptive = true
                    assertTrue(tree, tree.contains("background") && tree.contains("foreground"))
                }
                if (tree.contains("E: layer-list") && tree.contains("M50,0A50,50")) {
                    shaped++
                    // circle filled with the (live) background colour + foreground enlarged by -25%
                    assertTrue(tree, Regex("""fillColor\(0x01010404\)=@0x7f""").containsMatchIn(tree))
                    // -25% (0xe0000030, same bytes aapt2 writes for "-25%"; its dump shows it as 1.75%)
                    assertTrue(tree, tree.contains("inset(0x010104b5)=1.750000%"))
                }
            }
            assertTrue(sawAdaptive)
            // camera + phone, each in day and night files
            assertTrue("shaped copies: $shaped\n$dump", shaped >= 2)
            assertTrue(dump, dump.contains("drawable/camera_round") && dump.contains("drawable/phone_round"))
            val appfilter = run(aapt2, "dump", "xmltree", "--file", "res/xml/appfilter.xml", out.path)
            assertTrue(appfilter, appfilter.contains("calendar") && appfilter.contains("cal_x_"))
            assertEquals(true, out.length() > 0)
            if (i == 0) File(work, "dump.txt").writeText(dump)

            // Own icon colour: wallpaper references become fixed colours from the chosen palette.
            val palette = IconPalette.forSeed(0xFFE53935.toInt())
            assertTrue("palette covers the system tones: ${palette.size}", palette.size >= 65)
            val red = LiveBundle()
            SourceRes(listOf(srcApk)).use { src ->
                assertTrue(LiveCopier(src, "com.example.mypack", palette).collect("camera", "camera_red", red,
                    shape = ShapePaths.svg("CIRCLE")))
            }
            val redOut = File(work, "red$i.apk")
            ApkBuilder.buildUnsigned(PackSpec(
                packageName = "com.iconforge.pack.redtest", label = "Red test", versionCode = 1, versionName = "1",
                icons = listOf(PackIcon("static_icon", png)),
                mappings = listOf(PackMapping("com.android.camera", "com.android.camera.Camera", "camera_red")),
                appIconRes = "static_icon", live = red,
            ), redOut)
            val redDump = run(aapt2, "dump", "resources", redOut.path)
            assertFalse("no wallpaper colours left\n$redDump", redDump.contains("@0x0106"))
            assertTrue("fixed colours in day and night\n$redDump", Regex("""#ff[0-9a-f]{6}""").findAll(redDump).count() >= 4)
            Regex("""(res/lv/\S+\.xml)""").findAll(redDump).map { it.value }.distinct().forEach { path ->
                val tree = run(aapt2, "dump", "xmltree", "--file", path, redOut.path)
                assertFalse("xml colours replaced too\n$tree", tree.contains("@0x0106"))
            }
        }
    }
}
