package io.github.linuskromnow.linusexplorer.cloud

/**
 * OAuth client IDs of LinusExplorer's own registered cloud apps. These are public by design
 * (installed apps cannot keep a secret; PKCE protects the sign-in), so they live in the source.
 * Empty means "not registered yet": users then enter the key of their own app.
 */
object CloudKeys {
    /** Dropbox App Console → app → Settings → "App key". */
    const val DROPBOX_APP_KEY = "63ogn01v7eg456e"

    /** Microsoft Entra admin center → App registrations → app → "Application (client) ID". */
    const val ONEDRIVE_CLIENT_ID = "09352327-65f0-4316-aea5-eed685d0be41"
}
