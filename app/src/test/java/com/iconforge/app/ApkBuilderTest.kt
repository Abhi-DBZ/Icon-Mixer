package com.iconforge.app

import com.iconforge.app.export.ApkBuilder
import com.iconforge.app.export.PackIcon
import com.iconforge.app.export.PackMapping
import com.iconforge.app.export.PackSpec
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Properties
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Builds a sample icon-pack APK on the PC. The output (app/build/test-pack.apk) is then
 * checked with the SDK's aapt2 / apksigner — see README "Verifying the APK builder".
 */
class ApkBuilderTest {

    private fun png(rgb: Int): ByteArray {
        val img = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 48) for (y in 0 until 48) img.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun buildsSignedPack() {
        // Needs the private keystore/ folder (not in git); skipped without it.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "keystore/key.properties").isFile }
        Assume.assumeTrue("keystore/ not present", root != null)
        root!!
        val props = Properties().apply { File(root, "keystore/key.properties").inputStream().use { load(it) } }
        val ks = KeyStore.getInstance("PKCS12")
        File(root, "keystore/" + props.getProperty("storeFile")).inputStream().use {
            ks.load(it, props.getProperty("storePassword").toCharArray())
        }
        val alias = props.getProperty("keyAlias")
        val key = ks.getKey(alias, props.getProperty("keyPassword").toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate

        val spec = PackSpec(
            packageName = "com.iconforge.pack.test",
            label = "Test Theme",
            versionCode = 3,
            versionName = "1.3",
            icons = listOf(
                PackIcon("whatsapp", png(0xFF25D366.toInt())),
                PackIcon("camera", png(0xFF3366FF.toInt()), pngNight = png(0xFF0A1A44.toInt())),
                PackIcon("app_com_example_bank", png(0xFFFF8800.toInt())),
            ),
            mappings = listOf(
                PackMapping("com.whatsapp", "com.whatsapp.Main", "whatsapp"),
                PackMapping("com.android.camera", "com.android.camera.Camera", "camera"),
                PackMapping("com.example.bank", "com.example.bank.Splash", "app_com_example_bank"),
            ),
            appIconRes = "camera",
            extraFiles = mapOf("assets/iconforge/theme.json" to "{\"name\":\"Test Theme\"}".toByteArray()),
        )
        val outDir = File(root, "app/build").apply { mkdirs() }
        val unsigned = File(outDir, "test-pack-unsigned.apk")
        val signed = File(outDir, "test-pack.apk")
        ApkBuilder.buildUnsigned(spec, unsigned)
        ApkBuilder.sign(unsigned, signed, key, listOf(cert))
        assertTrue(signed.length() > unsigned.length())
    }

    @Test
    fun resNames() {
        assertTrue(ApkBuilder.resName("com.Whats-App!") == "com_whats_app")
        assertTrue(ApkBuilder.resName("9gag") == "i_9gag")
    }
}
