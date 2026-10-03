// Root build file. Plugins are declared here (apply false) so that the version
// is resolved once for the whole build, then applied in :app.
plugins {
    alias(libs.plugins.android.application) apply false
}
