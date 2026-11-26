package am.cyberhayq.chat.update

import android.content.pm.Signature
import java.util.jar.JarFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Android 9's APK signature check cannot run in Robolectric: its StrictJarFile reads no JAR
 * signature and its v2 verifier needs java.nio internals the JVM does not open. This reads the v1
 * signer with java.util.jar instead, so the rest of the archive parser (which asks for the check
 * only when GET_SIGNATURES is set) runs as on a device.
 */
@Implements(className = "android.util.apk.ApkSignatureVerifier", isInAndroidSdk = false, minSdk = 28, maxSdk = 28)
class ShadowApkSignatureVerifier {
    companion object {
        @JvmStatic
        @Implementation
        fun verify(apkPath: String, @Suppress("UNUSED_PARAMETER") minSignatureSchemeVersion: Int): Any {
            val certificates =
                JarFile(apkPath, true).use { jar ->
                    val entry = jar.getJarEntry("AndroidManifest.xml")
                    // the signers are known once the entry is read to its end
                    jar.getInputStream(entry).use { it.readBytes() }
                    entry.certificates
                }
            val signatures = certificates.map { Signature(it.encoded) }.toTypedArray()
            // PackageParser.SigningDetails(signatures, SignatureSchemeVersion.JAR)
            return Class.forName("android.content.pm.PackageParser\$SigningDetails")
                .getConstructor(Array<Signature>::class.java, Int::class.javaPrimitiveType)
                .newInstance(signatures, 1)
        }
    }
}
