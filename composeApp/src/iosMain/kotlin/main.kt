import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import com.achulkov.evomolior.root.App
import com.achulkov.evomolior.util.LocalNativeViewFactory
import com.achulkov.evomolior.util.NativeViewFactory
import com.achulkov.evomolior.util.SwiftLibDependencyFactory
import com.achulkov.evomolior.util.swiftLibDependenciesModule
import org.koin.core.KoinApplication
import platform.UIKit.UIViewController

fun MainViewController(nativeViewFactory: NativeViewFactory): UIViewController =
    ComposeUIViewController {
        CompositionLocalProvider(LocalNativeViewFactory provides nativeViewFactory) {
            App()
        }
    }

//This is called on application started on Swift side
fun KoinApplication.provideSwiftLibDependencyFactory(factory: SwiftLibDependencyFactory) =
    run { modules(swiftLibDependenciesModule(factory)) }