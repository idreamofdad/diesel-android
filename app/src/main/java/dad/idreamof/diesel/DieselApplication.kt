package dad.idreamof.diesel

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dad.idreamof.diesel.data.AppContainer

/**
 * Application entry point. Owns the [AppContainer] dependency graph and supplies Coil
 * with an [ImageLoader] backed by the app's authenticated OkHttp client, so portrait
 * requests carry the bearer token.
 */
class DieselApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(container.httpClient)
            .build()
}
