package patience.meshchat;

import android.app.Application;

import dagger.hilt.android.HiltAndroidApp;

/**
 * ============================================================================
 * MeshChatApplication — Application class with Hilt DI
 * ============================================================================
 *
 * @HiltAndroidApp triggers Hilt's code generation:
 *   - A base application class (Hilt_MeshChatApplication) is generated
 *     with DI container setup
 *   - All @AndroidEntryPoint-annotated Activities, Services, etc.
 *     can receive injected dependencies
 *
 * NOTE: Without the Hilt Gradle plugin (AGP 9.0+ incompatibility), we
 * pass the superclass explicitly and extend Hilt_MeshChatApplication.
 *
 * This class MUST be registered in AndroidManifest.xml:
 *   <application android:name=".MeshChatApplication" ... >
 *
 * ============================================================================
 */
@HiltAndroidApp(Application.class)
public class MeshChatApplication extends Hilt_MeshChatApplication {
}
